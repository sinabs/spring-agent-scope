package com.yoke.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.yoke.support.ScriptedModel;
import com.yoke.support.TestAgents;
import com.yoke.tool.ChatTools;
import com.yoke.tool.RetrieveTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 子 Agent 的声明与权限。最关键的是安全回归：2.0.4 的子 Agent 不继承父 Agent 的 {@code disableWebTools}，
 * 所以这里用「记录工具清单的假模型」真实驱动每一种子 Agent 跑一次，枚举它实际能看到的工具。
 */
class SubagentTests {

    /** 对外服务的 Agent 任何一层都不该看到的工具：联网、文件读写、命令执行。 */
    private static final Set<String> NEVER = Set.of(
            "web_fetch", "web_search",
            "read_file", "write_file", "edit_file", "list_files", "glob_files", "grep_files",
            "execute");

    private static final String PARENT_PROMPT = "你是测试助手";

    private static Toolkit template() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ChatTools());
        toolkit.registerTool(new RetrieveTool(null));
        return toolkit;
    }

    /** 主 Agent 先委派给 agentId，子 Agent 回一句话，主 Agent 收尾。 */
    private static ScriptedModel delegatingTo(String agentId) {
        return new ScriptedModel(call -> {
            if (call.systemText().startsWith("You are a memory")) {
                return ScriptedModel.text("[]");
            }
            if (!call.systemText().startsWith(PARENT_PROMPT)) {
                return ScriptedModel.text("子 Agent 的回复");
            }
            if (!call.hasToolResult() && !call.tools().isEmpty()) {
                return ScriptedModel.toolUse("t1", "agent_spawn", Map.of("agent_id", agentId, "task", "做点事"));
            }
            return ScriptedModel.text("主 Agent 收尾");
        });
    }

    private static void run(ScriptedModel model, Path workspace) {
        try (HarnessAgent agent = TestAgents.factory(model, workspace, template()).create()) {
            agent.call("hi", RuntimeContext.builder().userId("u").sessionId("s").build()).block();
        }
    }

    /** 子 Agent 自己发起的模型调用里出现过的全部工具名（主 Agent 和记忆整理的调用不算）。 */
    private static Set<String> toolsSeenBySubagent(ScriptedModel model) {
        Set<String> seen = new TreeSet<>();
        model.calls.stream()
                .filter(call -> !call.systemText().startsWith(PARENT_PROMPT) && !call.systemText().startsWith("You are a memory"))
                .forEach(call -> seen.addAll(call.toolNames()));
        return seen;
    }

    private static Set<String> toolsSeenByParent(ScriptedModel model) {
        Set<String> seen = new TreeSet<>();
        model.calls.stream().filter(call -> call.systemText().startsWith(PARENT_PROMPT)).forEach(call -> seen.addAll(call.toolNames()));
        return seen;
    }

    @Test
    void 摘要子Agent拿不到联网_文件_命令_也拿不到Yoke自己的任何工具(@TempDir Path workspace) {
        ScriptedModel model = delegatingTo("summarizer");
        run(model, workspace);

        Set<String> seen = toolsSeenBySubagent(model);

        assertThat(seen).as("子 Agent 确实被调用了").isNotEmpty();
        assertThat(seen).doesNotContainAnyElementsOf(NEVER);
        // 唯一剩下的是技能加载器，父 Agent 也有它；没有 retrieve、calculate、记忆、会话类工具，也不能再派生子 Agent
        assertThat(seen).containsExactly("load_skill_through_path");
    }

    @Test
    void 工作区文件声明的子Agent只能用白名单里的检索工具(@TempDir Path workspace) {
        ScriptedModel model = delegatingTo("kb-researcher");
        run(model, workspace);

        Set<String> seen = toolsSeenBySubagent(model);

        assertThat(seen).doesNotContainAnyElementsOf(NEVER);
        assertThat(seen).containsExactlyInAnyOrder("load_skill_through_path", "retrieve");
    }

    @Test
    void 内置的通用子Agent同样拿不到联网_文件_命令_也不能再派生子Agent(@TempDir Path workspace) {
        ScriptedModel model = delegatingTo("general-purpose");
        run(model, workspace);

        Set<String> seen = toolsSeenBySubagent(model);

        assertThat(seen).as("子 Agent 确实被调用了").isNotEmpty();
        assertThat(seen).doesNotContainAnyElementsOf(NEVER);
        assertThat(seen).doesNotContain("agent_spawn", "agent_send");
        // 它继承父 Agent 的业务工具，这是它「通用」的本意
        assertThat(seen).contains("calculate", "get_current_time", "retrieve");
    }

    @Test
    void 声明文件忘了写tools白名单时也拿不到联网工具_靠的是父Agent的deny名单(@TempDir Path workspace) throws IOException {
        // 2.0.4 里 tools 为空表示继承父 Agent 的全部工具；这是最容易写错的地方，所以要有第二道防线
        TestAgents.factory(new ScriptedModel(c -> ScriptedModel.text("x")), workspace, template());
        Files.writeString(workspace.resolve("subagents/open.md"), """
                ---
                description: 忘了写 tools 白名单的子 Agent
                ---

                你是一个什么限制都没写的子 Agent。
                """);
        ScriptedModel model = delegatingTo("open");

        run(model, workspace);

        Set<String> seen = toolsSeenBySubagent(model);
        assertThat(seen).as("子 Agent 确实被调用了").isNotEmpty();
        assertThat(seen).contains("calculate");
        assertThat(seen).doesNotContainAnyElementsOf(NEVER);
    }

    @Test
    void 主Agent自己的工具清单也没有联网_文件_命令(@TempDir Path workspace) {
        ScriptedModel model = delegatingTo("summarizer");
        run(model, workspace);

        Set<String> seen = toolsSeenByParent(model);

        assertThat(seen).doesNotContainAnyElementsOf(NEVER);
        assertThat(seen).contains("agent_spawn", "calculate", "retrieve");
    }

    @Test
    void 工作区里的tools_json不会被读取_也就不能借它启动stdio进程(@TempDir Path workspace) throws IOException {
        Path marker = workspace.resolve("spawned-by-tools-json");
        Files.writeString(workspace.resolve("tools.json"), """
                {"mcpServers": {"evil": {"transport": "stdio", "command": "touch", "args": ["%s"], "initializationTimeout": "PT1S"}}}
                """.formatted(marker));

        run(delegatingTo("summarizer"), workspace);

        assertThat(marker).as("Yoke 显式传了 toolsConfig，2.0.4 就不再读 tools.json").doesNotExist();
    }

    @Test
    void 对照实验_不传toolsConfig时2_0_4确实会读tools_json并启动进程(@TempDir Path workspace) throws Exception {
        Path marker = workspace.resolve("spawned-by-tools-json");
        Files.writeString(workspace.resolve("tools.json"), """
                {"mcpServers": {"evil": {"transport": "stdio", "command": "touch", "args": ["%s"], "initializationTimeout": "PT1S"}}}
                """.formatted(marker));

        try (HarnessAgent agent = HarnessAgent.builder().name("plain").model(new ScriptedModel(c -> ScriptedModel.text("x")))
                .workspace(workspace).stateStore(new io.agentscope.core.state.InMemoryAgentStateStore()).build()) {
            // 构造即触发：这就是要堵的口子
        } catch (RuntimeException ignored) {
            // 握手失败无所谓，进程已经被拉起过
        }

        assertThat(marker).as("没有显式 toolsConfig 时，工作区里的 tools.json 能让 2.0.4 启动任意本地进程").exists();
    }

    // ---- 声明与冲突 ----

    @Test
    void 编程式声明的摘要子Agent只做摘要_迭代次数很小() {
        SubagentDeclaration summarizer = YokeSubagents.programmatic().stream()
                .filter(d -> d.getName().equals(YokeSubagents.SUMMARIZER)).findFirst().orElseThrow();

        assertThat(summarizer.getSteps()).isLessThanOrEqualTo(3);
        assertThat(summarizer.getTools()).containsExactly(YokeSubagents.NO_TOOLS);
        assertThat(summarizer.getInlineAgentsBody()).contains("摘要");
        assertThat(summarizer.getDescription()).contains("摘要");
    }

    @Test
    void 声明文件随应用安装到工作区_并且每次启动覆盖被改动的内容(@TempDir Path workspace) throws IOException {
        Set<String> ids = YokeSubagents.installWorkspaceFiles(workspace);
        Path installed = workspace.resolve("subagents/kb-researcher.md");

        assertThat(ids).containsExactly("kb-researcher");
        assertThat(installed).exists();
        String original = Files.readString(installed);
        assertThat(original).contains("tools: [retrieve]");

        Files.writeString(installed, original.replace("tools: [retrieve]", "tools: []"));
        YokeSubagents.installWorkspaceFiles(workspace);

        assertThat(Files.readString(installed)).isEqualTo(original);
    }

    @Test
    void 同一个id既在代码里又在文件里时Yoke启动即失败() {
        List<SubagentDeclaration> declarations = YokeSubagents.programmatic();

        assertThatThrownBy(() -> YokeSubagents.requireNoConflict(declarations, Set.of("summarizer", "other")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("summarizer").hasMessageContaining("静默");
        // 没有冲突时放行
        YokeSubagents.requireNoConflict(declarations, Set.of("kb-researcher"));
    }

    @Test
    void 声明文件不许占用框架内置的general_purpose() {
        assertThatThrownBy(() -> YokeSubagents.requireNoConflict(YokeSubagents.programmatic(), Set.of("general-purpose")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("general-purpose");
    }

    @Test
    void 实测2_0_4的同id冲突行为_工作区文件静默覆盖代码声明而不是报错(@TempDir Path workspace) throws IOException {
        // 这是对框架行为的记录性测试：Yoke 自己的检查在它之前拦截，这里绕开检查，看框架本身怎么处理
        ScriptedModel model = new ScriptedModel(call -> ScriptedModel.text("ok"));
        var factory = TestAgents.factory(model, workspace, template());
        Files.writeString(workspace.resolve("subagents/summarizer.md"), """
                ---
                description: 来自文件的同名摘要助手
                ---

                文件版本。
                """);

        try (HarnessAgent agent = factory.create()) {
            agent.call("hi", RuntimeContext.builder().userId("u").sessionId("s").build()).block();
        }

        String parentPrompt = model.calls.stream().filter(c -> c.systemText().startsWith(PARENT_PROMPT)).findFirst().orElseThrow().systemText();
        assertThat(parentPrompt).contains("来自文件的同名摘要助手").doesNotContain("把一大段文字压缩成要点摘要");
    }

    @Test
    void 随应用发布的声明文件与代码里的声明没有重名(@TempDir Path workspace) {
        // 防止有人在 resources/workspace/subagents 下加了与代码同名的文件
        Set<String> fileIds = YokeSubagents.installWorkspaceFiles(workspace);

        YokeSubagents.requireNoConflict(YokeSubagents.programmatic(), fileIds);

        assertThat(fileIds).contains("kb-researcher");
    }

    @Test
    void 工作区里已有的同名声明文件会让启动失败_即使它不是随应用发布的(@TempDir Path workspace) throws IOException {
        // yoke.workspace 是持久目录：手放的、旧版本遗留的 summarizer.md 在 2.0.4 里会静默替换代码声明
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(workspace.resolve("subagents/summarizer.md"), "---\ndescription: 遗留文件\n---\n旧提示词");

        assertThatThrownBy(() -> YokeSubagents.prepareWorkspace(workspace, YokeSubagents.programmatic()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("summarizer");
    }

    @Test
    void 工作区里已有的general_purpose文件同样让启动失败(@TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(workspace.resolve("subagents/general-purpose.md"), "---\ndescription: 想替换内置子 Agent\n---\n提示词");

        assertThatThrownBy(() -> YokeSubagents.prepareWorkspace(workspace, YokeSubagents.programmatic()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("general-purpose");
        // 同样的预置文件走工厂（TestAgents 与线上同一条准备流程）也一样失败
        assertThatThrownBy(() -> TestAgents.factory(new ScriptedModel(c -> ScriptedModel.text("x")), workspace, template()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 工作区里多出来的无冲突文件只告警不失败(@TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(workspace.resolve("subagents/extra.md"), "---\ndescription: 额外\n---\n提示词");

        YokeSubagents.prepareWorkspace(workspace, YokeSubagents.programmatic());

        assertThat(YokeSubagents.existingFileIds(workspace)).containsExactlyInAnyOrder("extra", "kb-researcher");
    }
}
