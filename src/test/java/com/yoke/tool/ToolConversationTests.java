package com.yoke.tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.yoke.controller.ChatController;
import com.yoke.model.ChatEvent;
import com.yoke.model.ChatRequest;
import com.yoke.model.ChatResponse;
import com.yoke.support.McpTestServer;
import com.yoke.support.ScriptedModel;
import com.yoke.support.TestAgents;
import com.yoke.config.YokeProperties;
import com.yoke.mcp.McpConnections;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.http.codec.ServerSentEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 带工具的完整一轮对话：模型（脚本）先决定调用工具，真实的 HarnessAgent ReAct 循环执行工具、把结果回填，
 * 模型再据此作答。控制器、EventCodec 都是真实的；不调用真实模型，也不需要 Redis。
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

    private static List<ChatEvent> stream(ChatController controller, ChatRequest request) {
        return controller.stream(request).map(ServerSentEvent::data).collectList().block(Duration.ofSeconds(30));
    }

    @Test
    void 流式对话里工具被真实执行_结果回填给模型_事件里有调用和结果(@TempDir Path workspace) {
        ScriptedModel model = script("calculate", Map.of("expression", "(1 + 2) * 4"));
        ChatController controller = new ChatController(TestAgents.factory(model, workspace, template()));

        List<ChatEvent> events = stream(controller, new ChatRequest("s-calc", "帮我算 (1+2)*4"));

        ChatEvent.ToolCall call = events.stream().filter(ChatEvent.ToolCall.class::isInstance)
                .map(ChatEvent.ToolCall.class::cast).findFirst().orElseThrow();
        ChatEvent.ToolResult result = events.stream().filter(ChatEvent.ToolResult.class::isInstance)
                .map(ChatEvent.ToolResult.class::cast).findFirst().orElseThrow();
        assertThat(call.name()).isEqualTo("calculate");
        assertThat(call.arguments()).contains("(1 + 2) * 4");
        assertThat(result.id()).isEqualTo(call.id());
        assertThat(result.name()).isEqualTo("calculate");
        assertThat(result.state()).isEqualTo("success");
        assertThat(result.output()).contains("12");
        // 事件顺序：先调用，后结果，最后以 done 结束
        assertThat(events.indexOf(call)).isLessThan(events.indexOf(result));
        assertThat(events.getLast()).isInstanceOf(ChatEvent.Done.class);
        // 结果真的回填给了模型：第二次调用的输入里带着工具结果，模型据此作答
        assertThat(((ChatEvent.Done) events.getLast()).text()).contains("工具说：").contains("12");
        assertThat(model.calls.stream().filter(c -> c.hasToolResult()).findFirst().orElseThrow().lastToolResultText()).contains("12");
    }

    @Test
    void 非流式对话同样走工具并拿到最终回复(@TempDir Path workspace) {
        ScriptedModel model = script("calculate", Map.of("expression", "1 / 0"));
        ChatController controller = new ChatController(TestAgents.factory(model, workspace, template()));

        ChatResponse response = controller.chat(new ChatRequest("s-err", "算 1/0")).block(Duration.ofSeconds(30));

        // 工具把错误作为文本返回给模型，由模型转述；而不是让整次对话失败
        assertThat(response.reply()).contains("无法计算：除数不能为 0");
    }

    @Test
    void 请求里的userId经由RuntimeContext注入到工具_模型看不到也伪造不了(@TempDir Path workspace) {
        // 模型调用 list_my_notes 时还硬塞了一个 userId=default，试图读别人的笔记
        ScriptedModel model = script("list_my_notes", Map.of("limit", 5, "userId", "default"));
        ChatController controller = new ChatController(TestAgents.factory(model, workspace, template()));

        List<ChatEvent> alice = stream(controller, new ChatRequest("s-a", "我的笔记", "alice"));
        List<ChatEvent> fallback = stream(controller, new ChatRequest("s-d", "我的笔记"));

        assertThat(resultOf(alice)).contains("alice").contains("MCP").doesNotContain("Redis");
        assertThat(resultOf(fallback)).contains("default").contains("Redis").doesNotContain("MCP");
    }

    @Test
    void 带编号的检索结果经过完整一轮对话回到模型手里(@TempDir Path workspace) {
        var port = new com.yoke.knowledge.KeywordRetrievePort(
                com.yoke.knowledge.KnowledgeCorpus.loadFromClasspath("classpath*:knowledge/*.md"));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new RetrieveTool(port));
        ScriptedModel model = script("retrieve", Map.of("query", "对话历史存在哪里", "top_k", 2));
        ChatController controller = new ChatController(TestAgents.factory(model, workspace, toolkit));

        List<ChatEvent> events = stream(controller, new ChatRequest("s-r", "历史存哪"));

        String output = resultOf(events);
        assertThat(output).contains("[1] 来源：").contains("[2] 来源：").contains("Redis").doesNotContain("[3]");
    }

    @Test
    void MCP工具以服务端公布的原名出现在模型可见的工具清单里并能被调用(@TempDir Path workspace) {
        try (McpTestServer server = new McpTestServer();
                McpConnections mcp = McpConnections.connect(List.of(new YokeProperties.McpServer(
                        "demo", "streamable-http", server.url(), Duration.ofSeconds(10), Map.of())))) {
            Toolkit toolkit = template();
            mcp.registerInto(toolkit);
            ScriptedModel model = script("echo", Map.of("text", "你好 MCP"));
            ChatController controller = new ChatController(TestAgents.factory(model, workspace, toolkit));

            List<ChatEvent> events = stream(controller, new ChatRequest("s-mcp", "回显一下"));

            // 模型看到的是原名 echo，没有 demo__ 之类的前缀
            assertThat(model.calls.getFirst().toolNames()).contains("echo").noneMatch(name -> name.contains("__") && name.contains("echo"));
            assertThat(resultOf(events)).contains("echo: 你好 MCP");
        }
    }

    @Test
    void 未声明只读的MCP工具不会暴露给模型_主Agent和子Agent都看不到_即使模型硬调也不会执行(@TempDir Path workspace) {
        // 2.0.4 只放行声明了 readOnlyHint 的 MCP 工具，其余要用户确认；HTTP 接口没有确认通道，所以注册时就不暴露
        try (McpTestServer server = new McpTestServer();
                McpConnections mcp = McpConnections.connect(List.of(new YokeProperties.McpServer(
                        "demo", "streamable-http", server.url(), Duration.ofSeconds(10), Map.of())))) {
            Toolkit toolkit = template();
            mcp.registerInto(toolkit);

            // 主 Agent：清单里有 echo、没有 write_note；模型幻觉出对 write_note 的调用，只得到“工具不存在”的错误
            ScriptedModel parent = script("write_note", Map.of("text", "危险写入"));
            List<ChatEvent> events = stream(new ChatController(TestAgents.factory(parent, workspace, toolkit)),
                    new ChatRequest("s-hidden", "写一条笔记"));
            assertThat(parent.calls.getFirst().toolNames()).contains("echo").doesNotContain("write_note");
            assertThat(server.writeNoteCalls).hasValue(0);
            assertThat(events.getLast()).isInstanceOf(ChatEvent.Done.class);

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
            stream(new ChatController(TestAgents.factory(delegating, workspace, toolkit)), new ChatRequest("s-hidden-sub", "委派"));
            var subCalls = delegating.calls.stream()
                    .filter(c -> !c.systemText().startsWith("你是测试助手") && !c.systemText().startsWith("You are a memory")).toList();
            assertThat(subCalls).isNotEmpty();
            assertThat(subCalls).allSatisfy(c -> assertThat(c.toolNames()).contains("echo").doesNotContain("write_note"));
            assertThat(server.writeNoteCalls).hasValue(0);
        }
    }

    private static String resultOf(List<ChatEvent> events) {
        return events.stream().filter(ChatEvent.ToolResult.class::isInstance).map(ChatEvent.ToolResult.class::cast)
                .map(ChatEvent.ToolResult::output).findFirst().orElseThrow();
    }
}
