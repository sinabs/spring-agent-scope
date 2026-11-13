package com.yoke.a2a;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.yoke.a2a.runner.YokeAgentRunner;
import com.yoke.config.HarnessAgentFactory;
import com.yoke.support.ScriptedModel;
import com.yoke.support.TestAgents;
import com.yoke.tool.ChatTools;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 不依赖 Spring 和 Docker：直接测 AgentRunner 的适配逻辑（真实 HarnessAgent，脚本模型）。 */
class YokeAgentRunnerTests {

    private static Toolkit toolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ChatTools());
        return toolkit;
    }

    /** 本测试创建的那个 Agent 关闭完成时计数。 */
    private final CountDownLatch closed = new CountDownLatch(1);

    /**
     * 与线上同一个工厂，只是在 Agent 关闭完成时计数。runner 用 eager=false，结果先于关闭到达调用方，
     * 测试要等关闭完成才能安全地删掉临时工作区（关闭时还会往里写记忆文件）。
     */
    private YokeAgentRunner runner(ScriptedModel model, Path workspace, Duration timeout) {
        HarnessAgentFactory real = TestAgents.factory(model, workspace, toolkit());
        HarnessAgentFactory factory = mock(HarnessAgentFactory.class);
        when(factory.create()).thenAnswer(_ -> {
            io.agentscope.harness.agent.HarnessAgent agent = real.create();
            io.agentscope.harness.agent.HarnessAgent tracked =
                    mock(io.agentscope.harness.agent.HarnessAgent.class, delegatesTo(agent));
            doAnswer(_ -> {
                try {
                    agent.close();
                } finally {
                    closed.countDown();
                }
                return null;
            }).when(tracked).close();
            return tracked;
        });
        return new YokeAgentRunner(factory, "yoke", "测试", timeout);
    }

    private void awaitClosed() throws InterruptedException {
        assertThat(closed.await(10, TimeUnit.SECONDS)).as("Agent 已关闭").isTrue();
    }

    private static AgentRequestOptions options(String task, String user, String session) {
        AgentRequestOptions options = new AgentRequestOptions();
        options.setTaskId(task);
        options.setUserId(user);
        options.setSessionId(session);
        return options;
    }

    private static List<Msg> input(String text) {
        return List.of(new UserMessage(text));
    }

    private static String replyText(List<AgentEvent> events) {
        assertThat(events).hasSize(1).first().isInstanceOf(AgentResultEvent.class);
        return ((AgentResultEvent) events.getFirst()).getResult().getTextContent();
    }

    @Test
    void 只放行最终结果事件且只含文本(@TempDir Path workspace) throws InterruptedException {
        ScriptedModel model = new ScriptedModel(_ -> ScriptedModel.text("回复"));

        List<AgentEvent> events = runner(model, workspace, Duration.ofSeconds(30))
                .streamEvents(input("你好"), options("t1", "u1", "s1")).collectList().block();
        awaitClosed();

        assertThat(replyText(events)).isEqualTo("回复");
    }

    @Test
    void 模型的思考过程不会出现在对外的结果里(@TempDir Path workspace) throws InterruptedException {
        ScriptedModel model = new ScriptedModel(_ -> List.of(
                io.agentscope.core.message.ThinkingBlock.builder().thinking("内部思考不能外泄").build(),
                io.agentscope.core.message.TextBlock.builder().text("最终答案").build()));

        List<AgentEvent> events = runner(model, workspace, Duration.ofSeconds(30))
                .streamEvents(input("问"), options("t-think", "u", "s-think")).collectList().blockOptional().orElseThrow();
        awaitClosed();

        Msg result = ((AgentResultEvent) events.getFirst()).getResult();
        assertThat(result.getTextContent()).isEqualTo("最终答案");
        assertThat(result.getContent()).allMatch(io.agentscope.core.message.TextBlock.class::isInstance);
    }

    @Test
    void 不合法的用户或会话标识直接拒绝并记入失败台账(@TempDir Path workspace) {
        YokeAgentRunner runner = runner(new ScriptedModel(_ -> ScriptedModel.text("x")), workspace, Duration.ofSeconds(30));

        assertThatThrownBy(() -> runner.streamEvents(input("x"), options("t-bad-user", "../etc", "s1")).blockLast())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> runner.streamEvents(input("x"), options("t-bad-session", "u1", "a/b")).blockLast())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sessionId");
        assertThat(runner.takeFailure("t-bad-user")).isPresent();
        assertThat(runner.takeFailure("t-bad-user")).as("取走即清除").isEmpty();
        assertThat(runner.takeFailure("t-bad-session")).isPresent();
    }

    @Test
    void 没有文本内容时拒绝(@TempDir Path workspace) {
        YokeAgentRunner runner = runner(new ScriptedModel(_ -> ScriptedModel.text("x")), workspace, Duration.ofSeconds(30));

        assertThatThrownBy(() -> runner.streamEvents(List.of(), options("t-empty", "u", "s")).blockLast())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 缺失的userId按default处理_业务工具拿到的是它(@TempDir Path workspace) throws InterruptedException {
        // list_my_notes 按 RuntimeContext 里的 UserContext 取当前用户的笔记：default 用户有「给 Redis 加上密码」
        ScriptedModel model = new ScriptedModel(call -> {
            if (call.systemText().startsWith("You are a memory")) {
                return ScriptedModel.text("[]");
            }
            return call.hasToolResult() ? ScriptedModel.text(call.lastToolResultText())
                    : ScriptedModel.toolUse("c1", "list_my_notes", Map.of());
        });

        List<AgentEvent> events = runner(model, workspace, Duration.ofSeconds(30))
                .streamEvents(input("我的笔记"), options("t-notes", null, "s-notes")).collectList().block();
        awaitClosed();

        assertThat(replyText(events)).contains("给 Redis 加上密码");
    }

    @Test
    void 指定用户时业务工具拿到的是那个用户(@TempDir Path workspace) throws InterruptedException {
        ScriptedModel model = new ScriptedModel(call -> {
            if (call.systemText().startsWith("You are a memory")) {
                return ScriptedModel.text("[]");
            }
            return call.hasToolResult() ? ScriptedModel.text(call.lastToolResultText())
                    : ScriptedModel.toolUse("c1", "list_my_notes", Map.of());
        });

        List<AgentEvent> events = runner(model, workspace, Duration.ofSeconds(30))
                .streamEvents(input("我的笔记"), options("t-alice", "alice", "s-alice")).collectList().block();
        awaitClosed();

        assertThat(replyText(events)).contains("评估 MCP 服务的鉴权方式");
    }

    @Test
    void 超时后失败并记入台账(@TempDir Path workspace) {
        ScriptedModel slow = new ScriptedModel(_ -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ScriptedModel.text("太慢了");
        });
        YokeAgentRunner runner = runner(slow, workspace, Duration.ofMillis(500));

        assertThatThrownBy(() -> runner.streamEvents(input("慢"), options("t-slow", "u", "s-slow")).collectList().block())
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(runner.takeFailure("t-slow")).contains("智能体执行超时");
    }

    @Test
    void 正常结束_出错和取消时都关闭Agent() {
        HarnessAgentFactory factory = mock(HarnessAgentFactory.class);
        io.agentscope.harness.agent.HarnessAgent agent = mock(io.agentscope.harness.agent.HarnessAgent.class);
        when(factory.create()).thenReturn(agent);
        YokeAgentRunner runner = new YokeAgentRunner(factory, "yoke", "测试", Duration.ofSeconds(5));

        when(agent.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Flux.empty());
        runner.streamEvents(input("完成"), options("t-ok", "u", "s")).collectList().block();
        verify(agent).close();

        io.agentscope.harness.agent.HarnessAgent failing = mock(io.agentscope.harness.agent.HarnessAgent.class);
        when(factory.create()).thenReturn(failing);
        when(failing.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Flux.error(new IllegalStateException("boom")));
        assertThatThrownBy(() -> runner.streamEvents(input("出错"), options("t-err", "u", "s")).collectList().block())
                .isInstanceOf(IllegalStateException.class);
        verify(failing).close();

        io.agentscope.harness.agent.HarnessAgent hanging = mock(io.agentscope.harness.agent.HarnessAgent.class);
        when(factory.create()).thenReturn(hanging);
        when(hanging.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Flux.never());
        runner.streamEvents(input("取消"), options("t-cancel", "u", "s")).subscribe().dispose();
        verify(hanging).close();
    }

    @Test
    void 关闭Agent失败也不把已经成功的调用变成失败() {
        HarnessAgentFactory factory = mock(HarnessAgentFactory.class);
        io.agentscope.harness.agent.HarnessAgent agent = mock(io.agentscope.harness.agent.HarnessAgent.class);
        when(factory.create()).thenReturn(agent);
        when(agent.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(Msg.builder().role(MsgRole.ASSISTANT).textContent("好").build())));
        doThrow(new IllegalStateException("close failed")).when(agent).close();
        YokeAgentRunner runner = new YokeAgentRunner(factory, "yoke", "测试", Duration.ofSeconds(5));

        List<AgentEvent> events = runner.streamEvents(input("问"), options("t-close", "u", "s"))
                .collectList().blockOptional().orElseThrow();

        assertThat(events).hasSize(1);
        assertThat(((AgentResultEvent) events.getFirst()).getResult().getTextContent()).isEqualTo("好");
        verify(agent).close();
        assertThat(runner.takeFailure("t-close")).as("关闭失败不进失败台账").isEmpty();
    }
}
