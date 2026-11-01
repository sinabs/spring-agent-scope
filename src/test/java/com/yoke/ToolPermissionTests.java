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
 * 权限取舍：对外服务的 Agent 看不到文件读写、命令执行和联网工具；模型可见的工具清单是精确的集合。
 *
 * <p>清单 = 什么都不关的 HarnessAgent 的内置工具，减去被关掉的那一批，加上 Yoke 自己的工具。
 * 用精确集合断言，框架升级新增了内置工具、或者有人不小心多注册了工具，这里都会失败提醒。
 * 子 Agent 已经在第 03 轮打开（agent_spawn 等在清单里），它们自己能看到的工具另见 SubagentTests。
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
     * 被关掉的工具：文件读写、命令执行、联网。
     * 名字来自上面参照 Agent 的实测清单，由本类的第二个测试守着。
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "read_file", "write_file", "edit_file", "list_files", "glob_files", "grep_files",
            "execute", "web_search", "web_fetch");

    /**
     * 保留下来的框架内置工具，逐个写明（2.0.4 实测）：记忆、会话、技能加载、异步结果、子 Agent 和后台任务。
     * 写成字面量而不是从参照 Agent 推导，是为了让框架升级新增了任何内置工具时这里失败，逼着人来决定放不放行。
     */
    private static final Set<String> KEPT_BUILT_IN = Set.of(
            "memory_save", "memory_get", "memory_search",
            "session_history", "session_list", "session_search",
            "load_skill_through_path", "wait_async_results",
            "agent_spawn", "agent_send", "agent_list",
            "task_output", "task_cancel", "task_list");

    /** Yoke 自己注册的工具；默认配置下没有 MCP 工具。 */
    private static final Set<String> OWN_TOOLS = Set.of("get_current_time", "calculate", "list_my_notes", "retrieve");

    private Set<String> exposedTools() {
        controller.chat(new ChatRequest("tools-" + UUID.randomUUID(), "你有哪些工具")).block();
        // 回复之后框架还会异步发起记忆整理之类不带工具的模型调用，所以取最后一次带工具的调用
        return model.receivedTools.stream().filter(tools -> !tools.isEmpty())
                .reduce((first, second) -> second).map(TreeSet::new).orElseThrow();
    }

    @Test
    void 模型看不到文件_命令_联网工具() {
        Set<String> exposed = exposedTools();

        assertThat(exposed).as("模型确实拿到了工具清单").isNotEmpty();
        assertThat(exposed).doesNotContainAnyElementsOf(FORBIDDEN);
    }

    @Test
    void 模型可见的工具清单是内置保留项加Yoke自己的工具_不多不少() {
        Set<String> expected = new TreeSet<>(KEPT_BUILT_IN);
        expected.addAll(OWN_TOOLS);

        assertThat(exposedTools()).isEqualTo(expected);
    }

    @Test
    void 内置保留项加被关掉的项恰好是不加限制时的全部内置工具(@TempDir Path workspace) {
        Set<String> all = new TreeSet<>(KEPT_BUILT_IN);
        all.addAll(FORBIDDEN);

        assertThat(toolsOfUnrestrictedAgent(workspace)).isEqualTo(all);
    }

    @Test
    void 被关掉的工具恰好是不加限制时多出来的那一批(@TempDir Path workspace) {
        Set<String> exposed = exposedTools();

        Set<String> removed = toolsOfUnrestrictedAgent(workspace);
        removed.removeAll(exposed);

        // 框架以后新增了别的文件类工具时，这里会提醒我们把它也关掉
        assertThat(removed).isEqualTo(FORBIDDEN);
    }
}
