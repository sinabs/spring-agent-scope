package com.yoke.middleware;

import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoggingMiddlewareTests {

    private final LoggingMiddleware middleware = new LoggingMiddleware();
    private final Agent agent = mock(Agent.class);
    private final RuntimeContext ctx = RuntimeContext.builder().userId("alice").sessionId("s-1").build();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingMiddleware.class);
    private Level originalLevel;

    @BeforeEach
    void 挂上日志收集器() {
        when(agent.getAgentId()).thenReturn("agent-1");
        when(agent.getName()).thenReturn("Yoke");
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void 还原日志() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void 整次调用完成时记录agentId和会话以及耗时() {
        middleware.onAgent(agent, ctx, new AgentInput(List.of()), _ -> Flux.empty()).blockLast();

        assertThat(messages()).singleElement().satisfies(message -> assertThat(message)
                .contains("agent 完成", "agent=Yoke", "agentId=agent-1", "userId=alice", "sessionId=s-1", "耗时="));
    }

    @Test
    void 模型调用记录模型名() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("gpt-test");

        middleware.onModelCall(agent, ctx, new ModelCallInput(List.of(), List.of(), null, model),
                _ -> Flux.empty()).blockLast();

        assertThat(messages()).singleElement().satisfies(message -> assertThat(message)
                .contains("model 完成", "model=gpt-test"));
    }

    @Test
    void 工具调用只记录工具名不记录参数() {
        ToolUseBlock call = ToolUseBlock.builder().id("c1").name("memory_search")
                .input(Map.of("query", "我的银行卡密码是123456")).build();

        middleware.onActing(agent, ctx, new ActingInput(List.of(call)), _ -> Flux.empty()).blockLast();

        assertThat(messages()).singleElement().satisfies(message -> assertThat(message)
                .contains("acting 完成", "tools=[memory_search]")
                .doesNotContain("123456", "银行卡", "query"));
    }

    @Test
    void 出错时记录warn且异常继续向上抛() {
        assertThatThrownBy(() -> middleware.onAgent(agent, ctx, new AgentInput(List.of()),
                _ -> Flux.error(new IllegalStateException("密钥是 sk-secret"))).blockLast())
                .hasMessage("密钥是 sk-secret");

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("agent 出错", "IllegalStateException")
                    .as("只记异常类型，不记可能含敏感信息的异常消息").doesNotContain("sk-secret");
        });
    }

    @Test
    void 被取消时记录被取消() {
        Flux<AgentEvent> flux = middleware.onAgent(agent, ctx, new AgentInput(List.of()), _ -> Flux.never());

        flux.subscribe().dispose();

        assertThat(messages()).singleElement().satisfies(message -> assertThat(message).contains("agent 被取消"));
    }

    @Test
    void 订阅之前不计时也不打日志() {
        middleware.onAgent(agent, ctx, new AgentInput(List.of()), _ -> Flux.empty());

        assertThat(logs.list).isEmpty();
    }
}
