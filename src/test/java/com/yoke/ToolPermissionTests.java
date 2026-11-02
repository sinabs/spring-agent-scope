package com.yoke;

import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.yoke.controller.ChatController;
import com.yoke.model.ChatRequest;
import com.yoke.support.RecordingModel;
import com.yoke.support.RedisIntegrationTest;
import com.yoke.support.StubModelConfiguration;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限取舍：对外服务的 Agent 看不到文件读写、命令执行和联网工具。
 * 以「什么都不关的 HarnessAgent 比线上多出哪些工具」作为参照，避免硬编码工具名后框架改名而悄悄失效。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(StubModelConfiguration.class)
class ToolPermissionTests extends RedisIntegrationTest {

    @Autowired
    ChatController controller;

    @Autowired
    RecordingModel model;

    private static Set<String> toolsOfUnrestrictedAgent(Path workspace) {
        RecordingModel reference = new RecordingModel();
        try (HarnessAgent agent = HarnessAgent.builder().name("reference").model(reference)
                .workspace(workspace).stateStore(new InMemoryAgentStateStore()).build()) {
            agent.call("hi", RuntimeContext.builder().userId("u").sessionId("s").build()).block();
        }
        return new TreeSet<>(reference.receivedTools.getFirst());
    }

    /**
     * 被关掉的工具：文件读写、命令执行、联网，以及子 Agent 相关的（子 Agent 不继承联网工具的关闭）。
     * 名字来自上面参照 Agent 的实测清单，由本类的第二个测试守着。
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "read_file", "write_file", "edit_file", "list_files", "glob_files", "grep_files",
            "execute", "web_search", "web_fetch",
            "agent_spawn", "agent_send", "agent_list", "task_cancel", "task_list", "task_output");

    @Test
    void 模型看不到文件_命令_联网和子Agent工具() {
        controller.chat(new ChatRequest("tools-" + UUID.randomUUID(), "你有哪些工具")).block();

        // 回复之后框架还会异步发起记忆整理之类不带工具的模型调用，所以取最后一次带工具的调用
        Set<String> exposed = model.receivedTools.stream().filter(tools -> !tools.isEmpty())
                .reduce((first, second) -> second).map(TreeSet::new).orElseThrow();

        assertThat(exposed).as("模型确实拿到了工具清单").isNotEmpty();
        assertThat(exposed).doesNotContainAnyElementsOf(FORBIDDEN);
    }

    @Test
    void 被关掉的工具恰好是不加限制时多出来的那一批(@TempDir Path workspace) {
        controller.chat(new ChatRequest("tools-" + UUID.randomUUID(), "你有哪些工具")).block();
        Set<String> exposed = model.receivedTools.stream().filter(tools -> !tools.isEmpty())
                .reduce((first, second) -> second).map(TreeSet::new).orElseThrow();

        Set<String> removed = toolsOfUnrestrictedAgent(workspace);
        removed.removeAll(exposed);

        // 框架以后新增了别的文件类工具时，这里会提醒我们把它也关掉
        assertThat(removed).isEqualTo(FORBIDDEN);
    }

    @Test
    void 模型能看到天气工具且每次新建的Agent都带着它() {
        // 连发两次：每个请求新建一个 Agent，共享的 Toolkit 重复使用也不能丢工具或重复注册
        for (int i = 0; i < 2; i++) {
            model.receivedTools.clear();
            controller.chat(new ChatRequest("weather-" + UUID.randomUUID(), "你有哪些工具")).block();

            Set<String> exposed = model.receivedTools.stream().filter(tools -> !tools.isEmpty())
                    .reduce((first, second) -> second).map(TreeSet::new).orElseThrow();
            assertThat(exposed).contains("get_weather");
        }
    }
}
