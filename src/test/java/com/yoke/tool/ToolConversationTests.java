package com.yoke.tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.yoke.controller.ChatController;
import com.yoke.knowledge.retrieve.KeywordRetrievePort;
import com.yoke.model.ChatRequest;
import com.yoke.model.ChatResponse;
import com.yoke.support.AguiTestSupport;
import com.yoke.support.McpTestServer;
import com.yoke.support.ScriptedModel;
import com.yoke.support.TestAgents;
import com.yoke.config.YokeProperties;
import com.yoke.mcp.McpConnections;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 带工具的完整一轮对话：模型（脚本）先决定调用工具，真实的 HarnessAgent ReAct 循环执行工具、把结果回填，
 * 模型再据此作答。控制器、AG-UI 适配器都是真实的；不调用真实模型，也不需要 Redis。
 */
class ToolConversationTests {

    private static Toolkit template() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ChatTools());
        toolkit.registerTool(new RetrieveTool(null));
        return toolkit;
    }

    private static ScriptedModel script(String tool, Map<String, Object> input) {
        return new ScriptedModel(call -> {
            if (call.systemText().startsWith("You are a memory")) {
                return ScriptedModel.text("[]");
            }
            if (!call.hasToolResult()) {
                return ScriptedModel.toolUse("call-1", tool, input);
            }
            return ScriptedModel.text("工具说：" + call.lastToolResultText());
        });
    }

    private static List<Map<String, Object>> stream(ChatController controller, String threadId, String text) {
        return AguiTestSupport.run(controller, threadId, text);
    }

    private static ChatController controller(ScriptedModel model, Path workspace, Toolkit toolkit) {
        return AguiTestSupport.controller(TestAgents.factory(model, workspace, toolkit));
    }

    @Test
    void 流式对话里工具被真实执行_结果回填给模型_事件里有调用和结果(@TempDir Path workspace) {
        ScriptedModel model = script("calculate", Map.of("expression", "(1 + 2) * 4"));
        ChatController controller = controller(model, workspace, template());

        List<Map<String, Object>> events = stream(controller, "s-calc", "帮我算 (1+2)*4");

        Map<String, Object> call = AguiTestSupport.first(events, "TOOL_CALL_START");
        Map<String, Object> result = AguiTestSupport.first(events, "TOOL_CALL_RESULT");
        assertThat(call.get("toolCallName")).isEqualTo("calculate");
        assertThat(AguiTestSupport.toolArguments(events)).contains("(1 + 2) * 4");
        assertThat(result.get("toolCallId")).isEqualTo(call.get("toolCallId"));
        assertThat(String.valueOf(result.get("content"))).contains("12");
        // 事件顺序：先调用，后结果，最后是 RUN_FINISHED（没有 done 事件，全文由客户端拼接）
        List<String> types = events.stream().map(e -> (String) e.get("type")).toList();
        assertThat(types.indexOf("TOOL_CALL_START")).isLessThan(types.indexOf("TOOL_CALL_RESULT"));
        assertThat(types.getLast()).isEqualTo("RUN_FINISHED");
        // 结果真的回填给了模型：第二次调用的输入里带着工具结果，模型据此作答
        assertThat(AguiTestSupport.text(events)).contains("工具说：").contains("12");
        assertThat(model.calls.stream().filter(ScriptedModel.Call::hasToolResult).findFirst().orElseThrow().lastToolResultText()).contains("12");
    }

    @Test
    void 非流式对话同样走工具并拿到最终回复(@TempDir Path workspace) {
        ScriptedModel model = script("calculate", Map.of("expression", "1 / 0"));
        ChatController controller = controller(model, workspace, template());

        ChatResponse response = controller.chat(new ChatRequest("s-err", "算 1/0")).blockOptional(Duration.ofSeconds(30)).orElseThrow();

        // 工具把错误作为文本返回给模型，由模型转述；而不是让整次对话失败
        assertThat(response.reply()).contains("无法计算：除数不能为 0");
    }

    @Test
    void 请求里的userId经由RuntimeContext注入到工具_模型看不到也伪造不了(@TempDir Path workspace) {
        // 模型调用 list_my_notes 时还硬塞了一个 userId=default，试图读别人的笔记
        ScriptedModel model = script("list_my_notes", Map.of("limit", 5, "userId", "default"));
        ChatController controller = controller(model, workspace, template());

        // 流式接口固定归属 default 用户，要指定 userId 只能走 /api/chat；脚本模型把工具结果原样放进回复
        String alice = controller.chat(new ChatRequest("s-a", "我的笔记", "alice")).blockOptional(Duration.ofSeconds(30)).orElseThrow().reply();
        String fallback = controller.chat(new ChatRequest("s-d", "我的笔记")).blockOptional(Duration.ofSeconds(30)).orElseThrow().reply();

        assertThat(alice).contains("alice").contains("MCP").doesNotContain("Redis");
        assertThat(fallback).contains("default").contains("Redis").doesNotContain("MCP");
    }

    @Test
    void 带稳定编号的检索结果经过完整一轮对话回到模型手里(@TempDir Path workspace) {
        var port = new KeywordRetrievePort(
                com.yoke.knowledge.KnowledgeCorpus.loadFromClasspath("classpath*:knowledge/*.md"));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new RetrieveTool(port));
        ScriptedModel model = script("retrieve", Map.of("query", "对话历史存在哪里", "top_k", 2));
        ChatController controller = controller(model, workspace, toolkit);

        List<Map<String, Object>> events = stream(controller, "s-r", "历史存哪");

        String output = resultOf(events);
        // 模型要的是 top_k=2，结果里正好两条带编号的资料，少了多了都说明条数没有传到检索端口
        assertThat(Pattern.compile("\\[[\\w-]+\\.md#[0-9a-f]{8}] 来源：").matcher(output).results().count()).isEqualTo(2);
        assertThat(output).contains("Redis").doesNotContain("[1]").doesNotContain("[2]");
    }

    @Test
    void MCP工具以服务端公布的原名出现在模型可见的工具清单里并能被调用(@TempDir Path workspace) {
        try (McpTestServer server = new McpTestServer();
                McpConnections mcp = McpConnections.connect(List.of(new YokeProperties.McpServer(
                        "demo", "streamable-http", server.url(), Duration.ofSeconds(10), Map.of())))) {
            Toolkit toolkit = template();
            mcp.registerInto(toolkit);
            ScriptedModel model = script("echo", Map.of("text", "你好 MCP"));
            ChatController controller = controller(model, workspace, toolkit);

            List<Map<String, Object>> events = stream(controller, "s-mcp", "回显一下");

            // 模型看到的是原名 echo，没有 demo__ 之类的前缀
            assertThat(model.calls.getFirst().toolNames()).contains("echo").noneMatch(name -> name.contains("__") && name.contains("echo"));
            assertThat(resultOf(events)).contains("echo: 你好 MCP");
        }
    }

    @Test
    void 未声明只读的MCP工具不会暴露给模型_主Agent和子Agent都看不到_即使模型硬调也不会执行(@TempDir Path workspace) {
        // AgentScope 只放行声明了 readOnlyHint 的 MCP 工具，其余要用户确认；HTTP 接口没有确认通道，所以注册时就不暴露
        try (McpTestServer server = new McpTestServer();
                McpConnections mcp = McpConnections.connect(List.of(new YokeProperties.McpServer(
                        "demo", "streamable-http", server.url(), Duration.ofSeconds(10), Map.of())))) {
            Toolkit toolkit = template();
            mcp.registerInto(toolkit);

            // 主 Agent：清单里有 echo、没有 write_note；模型幻觉出对 write_note 的调用，只得到“工具不存在”的错误
            ScriptedModel parent = script("write_note", Map.of("text", "危险写入"));
            List<Map<String, Object>> events = stream(controller(parent, workspace, toolkit), "s-hidden", "写一条笔记");
            assertThat(parent.calls.getFirst().toolNames()).contains("echo").doesNotContain("write_note");
            assertThat(server.writeNoteCalls).hasValue(0);
            assertThat(events.getLast().get("type")).isEqualTo("RUN_FINISHED");

            // 子 Agent（内置 general-purpose 继承父 Agent 的工具）：同样只有 echo
            ScriptedModel delegating = new ScriptedModel(call -> {
                if (call.systemText().startsWith("You are a memory")) {
                    return ScriptedModel.text("[]");
                }
                if (call.systemText().startsWith("你是测试助手")) {
                    return call.hasToolResult() ? ScriptedModel.text("收尾")
                            : ScriptedModel.toolUse("t1", "agent_spawn", Map.of("agent_id", "general-purpose", "task", "x"));
                }
                return ScriptedModel.text("子回复");
            });
            stream(controller(delegating, workspace, toolkit), "s-hidden-sub", "委派");
            var subCalls = delegating.calls.stream()
                    .filter(c -> !c.systemText().startsWith("你是测试助手") && !c.systemText().startsWith("You are a memory")).toList();
            assertThat(subCalls).isNotEmpty();
            assertThat(subCalls).allSatisfy(c -> assertThat(c.toolNames()).contains("echo").doesNotContain("write_note"));
            assertThat(server.writeNoteCalls).hasValue(0);
        }
    }

    private static String resultOf(List<Map<String, Object>> events) {
        return String.valueOf(AguiTestSupport.first(events, "TOOL_CALL_RESULT").get("content"));
    }
}
