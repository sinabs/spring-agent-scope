package com.yoke;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.yoke.controller.ChatController;
import com.yoke.model.ChatEvent;
import com.yoke.model.ChatRequest;
import com.yoke.support.McpTestServer;
import com.yoke.support.RedisIntegrationTest;
import com.yoke.support.ScriptedModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整个应用上下文（真实 Redis、真实的 Spring 装配）加脚本化的假模型：证明工具、知识库、UserContext 在真实装配下是连着的。
 * 单元层面的测试各自把依赖手工接好，接线错误（比如检索工具没拿到检索端口）只有这一层能发现。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ToolsEndToEndTests.Scripted.class)
class ToolsEndToEndTests extends RedisIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Scripted {

        @Bean
        ScriptedModel scriptedModel() {
            return new ScriptedModel(call -> {
                if (call.systemText().startsWith("You are a memory")) {
                    return ScriptedModel.text("[]");
                }
                String question = call.messages().stream().filter(m -> m.getRole() == io.agentscope.core.message.MsgRole.USER)
                        .map(io.agentscope.core.message.Msg::getTextContent).reduce("", (a, b) -> b);
                if (call.hasToolResult()) {
                    return ScriptedModel.text("据资料：" + call.lastToolResultText());
                }
                if (question.contains("回显")) {
                    return ScriptedModel.toolUse("m1", "echo", Map.of("text", "来自端到端"));
                }
                if (question.contains("笔记")) {
                    return ScriptedModel.toolUse("n1", "list_my_notes", Map.of());
                }
                return ScriptedModel.toolUse("r1", "retrieve", Map.of("query", question, "top_k", 2));
            });
        }
    }

    /** 真实的进程内 MCP 服务，应用上下文按配置连上它；先于上下文启动，类结束时关闭。 */
    static final McpTestServer MCP = new McpTestServer();

    @DynamicPropertySource
    static void mcpProperties(DynamicPropertyRegistry registry) {
        registry.add("yoke.mcp.servers[0].name", () -> "demo");
        registry.add("yoke.mcp.servers[0].transport", () -> "streamable-http");
        registry.add("yoke.mcp.servers[0].url", MCP::url);
    }

    @AfterAll
    static void stopMcp() {
        MCP.close();
    }

    @Autowired
    ChatController controller;

    private List<ChatEvent> stream(ChatRequest request) {
        return controller.stream(request).map(ServerSentEvent::data).collectList().block(Duration.ofSeconds(60));
    }

    private static String toolOutput(List<ChatEvent> events) {
        return events.stream().filter(ChatEvent.ToolResult.class::isInstance).map(ChatEvent.ToolResult.class::cast)
                .map(ChatEvent.ToolResult::output).findFirst().orElseThrow();
    }

    @Test
    void 检索工具在真实装配下连着知识库_返回带编号的资料() {
        List<ChatEvent> events = stream(new ChatRequest("e2e-r-" + System.nanoTime(), "对话历史存在哪里"));

        ChatEvent.ToolCall call = events.stream().filter(ChatEvent.ToolCall.class::isInstance)
                .map(ChatEvent.ToolCall.class::cast).findFirst().orElseThrow();
        ChatEvent.ToolResult result = events.stream().filter(ChatEvent.ToolResult.class::isInstance)
                .map(ChatEvent.ToolResult.class::cast).findFirst().orElseThrow();
        assertThat(call.name()).isEqualTo("retrieve");
        assertThat(call.arguments()).contains("对话历史存在哪里").contains("\"top_k\":2");
        assertThat(result.id()).isEqualTo(call.id());
        assertThat(events.indexOf(call)).as("toolCall 先于 toolResult").isLessThan(events.indexOf(result));
        assertThat(result.output()).contains("[1] 来源：").contains("Redis").doesNotContain("尚未配置");
        assertThat(events.getLast()).isInstanceOf(ChatEvent.Done.class);
    }

    @Test
    void 配置的只读MCP工具在真实装配下经过完整的ReAct循环被调用() {
        List<ChatEvent> events = stream(new ChatRequest("e2e-m-" + System.nanoTime(), "请回显一下"));

        ChatEvent.ToolCall call = events.stream().filter(ChatEvent.ToolCall.class::isInstance)
                .map(ChatEvent.ToolCall.class::cast).findFirst().orElseThrow();
        ChatEvent.ToolResult result = events.stream().filter(ChatEvent.ToolResult.class::isInstance)
                .map(ChatEvent.ToolResult.class::cast).findFirst().orElseThrow();
        assertThat(call.name()).isEqualTo("echo");
        assertThat(result.id()).isEqualTo(call.id());
        assertThat(result.output()).contains("echo: 来自端到端");
        assertThat(events.indexOf(call)).isLessThan(events.indexOf(result));
        assertThat(((ChatEvent.Done) events.getLast()).text()).contains("echo: 来自端到端");
        assertThat(MCP.writeNoteCalls).hasValue(0);
    }

    @Test
    void 请求里的userId在真实装配下传到了工具() {
        List<ChatEvent> alice = stream(new ChatRequest("e2e-a-" + System.nanoTime(), "我的笔记", "alice"));

        assertThat(toolOutput(alice)).contains("alice").contains("MCP");
    }
}
