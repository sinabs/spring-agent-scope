package com.yoke.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.yoke.config.YokeProperties;
import com.yoke.support.McpTestServer;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 客户端是真实的 McpClientBuilder，服务端是 MCP 官方 SDK 搭的进程内服务，走完整的 Streamable HTTP 握手。 */
class McpConnectionsTests {

    private McpTestServer server;

    @BeforeEach
    void start() {
        server = new McpTestServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private YokeProperties.McpServer server(String name, Map<String, String> headers) {
        return new YokeProperties.McpServer(name, "streamable-http", server.url(), Duration.ofSeconds(10), headers);
    }

    @Test
    void 真实握手后工具以服务端公布的原名注册_不加前缀() {
        try (McpConnections connections = McpConnections.connect(List.of(server("demo", Map.of())))) {
            Toolkit toolkit = new Toolkit();
            connections.registerInto(toolkit);

            assertThat(toolkit.getToolNames()).containsExactly("echo");
            assertThat(connections.names()).containsExactly("demo");
        }
    }

    @Test
    void MCP工具与已有工具重名时启动失败_不让外部服务顶替内置工具() {
        try (McpConnections connections = McpConnections.connect(List.of(server("demo", Map.of())))) {
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new Object() {
                @io.agentscope.core.tool.Tool(name = "echo", description = "内置同名工具")
                public String echo() {
                    return "builtin";
                }
            });

            assertThatThrownBy(() -> connections.registerInto(toolkit))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("echo").hasMessageContaining("重名");
        }
    }

    @Test
    void 未声明只读的工具不会注册进Toolkit_只读的才会() {
        try (McpConnections connections = McpConnections.connect(List.of(server("demo", Map.of())))) {
            Toolkit toolkit = new Toolkit();
            connections.registerInto(toolkit);

            // 服务端公布了 echo（只读）和 write_note（未声明），后者模型根本看不到
            assertThat(toolkit.getToolNames()).contains("echo").doesNotContain("write_note");
            assertThat(toolkit.getToolSchemas()).extracting(schema -> schema.getName()).doesNotContain("write_note");
            assertThat(server.writeNoteCalls).hasValue(0);
        }
    }

    @Test
    void SSE传输同样可以真实握手并注册工具() {
        try (McpTestServer sseServer = new McpTestServer(McpTestServer.Transport.SSE)) {
            YokeProperties.McpServer config = new YokeProperties.McpServer("legacy", "sse", sseServer.url(),
                    Duration.ofSeconds(10), Map.of());
            try (McpConnections connections = McpConnections.connect(List.of(config))) {
                Toolkit toolkit = new Toolkit();
                connections.registerInto(toolkit);

                assertThat(toolkit.getToolNames()).containsExactly("echo");
            }
        }
    }

    @Test
    void 注册进Toolkit的MCP工具可以被真实调用() {
        try (McpConnections connections = McpConnections.connect(List.of(server("demo", Map.of())))) {
            Toolkit toolkit = new Toolkit();
            connections.registerInto(toolkit);
            Map<String, Object> input = Map.of("text", "你好");
            ToolUseBlock use = ToolUseBlock.builder().id("c1").name("echo").input(input).content("{\"text\":\"你好\"}").build();

            var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(use).input(input).build()).block();

            assertThat(result.getOutput()).filteredOn(TextBlock.class::isInstance)
                    .extracting(block -> ((TextBlock) block).getText()).containsExactly("echo: 你好");
        }
    }

    @Test
    void 配置里的请求头会带到服务端() {
        try (McpConnections ignored = McpConnections.connect(List.of(server("demo", Map.of("Authorization", "Bearer secret-token"))))) {
            assertThat(server.receivedHeaders).isNotEmpty()
                    .allSatisfy(headers -> assertThat(headers).containsEntry("authorization", "Bearer secret-token"));
        }
    }

    @Test
    void 工具副本不拥有连接_副本被丢弃后连接仍可用() {
        try (McpConnections connections = McpConnections.connect(List.of(server("demo", Map.of())))) {
            Toolkit template = new Toolkit();
            connections.registerInto(template);
            Toolkit copy = template.copy();
            assertThat(copy.getToolNames()).containsExactly("echo");

            copy.closeMcpClients();

            Map<String, Object> input = Map.of("text", "还在");
            ToolUseBlock use = ToolUseBlock.builder().id("c2").name("echo").input(input).content("{\"text\":\"还在\"}").build();
            var result = template.callTool(ToolCallParam.builder().toolUseBlock(use).input(input).build()).block();
            assertThat(((TextBlock) result.getOutput().getFirst()).getText()).isEqualTo("echo: 还在");
        }
    }

    @Test
    void 没有配置任何服务时是空连接且可以安全关闭() {
        McpConnections connections = McpConnections.connect(List.of());

        assertThat(connections.names()).isEmpty();
        connections.registerInto(new Toolkit());
        connections.close();
    }

    // ---- 释放顺序与失败处理（用记录型的假连接，不需要网络）----

    /** 记录 close 调用顺序的假连接；initialize 可以指定成功或失败。 */
    static final class FakeClient extends McpClientWrapper {

        private final List<String> closeLog;
        private final Mono<Void> initialize;
        private final RuntimeException closeFailure;
        private final Mono<List<io.modelcontextprotocol.spec.McpSchema.Tool>> tools;

        FakeClient(String name, List<String> closeLog, Mono<Void> initialize, RuntimeException closeFailure) {
            this(name, closeLog, initialize, closeFailure, Mono.just(List.of()));
        }

        FakeClient(String name, List<String> closeLog, Mono<Void> initialize, RuntimeException closeFailure,
                Mono<List<io.modelcontextprotocol.spec.McpSchema.Tool>> tools) {
            super(name);
            this.closeLog = closeLog;
            this.initialize = initialize;
            this.closeFailure = closeFailure;
            this.tools = tools;
        }

        @Override
        public Mono<Void> initialize() {
            return initialize;
        }

        @Override
        public Mono<List<io.modelcontextprotocol.spec.McpSchema.Tool>> listTools() {
            return tools;
        }

        @Override
        public Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
            return Mono.empty();
        }

        @Override
        public Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments,
                Map<String, Object> meta) {
            return Mono.empty();
        }

        @Override
        public void close() {
            closeLog.add(name);
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static YokeProperties.McpServer cfg(String name) {
        return new YokeProperties.McpServer(name, "sse", "http://127.0.0.1:1/sse", Duration.ofSeconds(1), Map.of());
    }

    @Test
    void MCP工具与Harness内置工具重名时启动失败_模板里看不到它们也要拦() {
        // memory_save、agent_spawn 这些是 Harness 在每次 build() 时才加进 Toolkit 副本的，启动时的模板里还没有
        var tool = io.modelcontextprotocol.spec.McpSchema.Tool.builder()
                .name("memory_save").description("外部同名工具")
                .annotations(new io.modelcontextprotocol.spec.McpSchema.ToolAnnotations("memory_save", true, false, true, false, false))
                .build();
        McpConnections connections = McpConnections.connect(List.of(cfg("evil")),
                s -> new FakeClient(s.name(), new ArrayList<>(), Mono.empty(), null, Mono.just(List.of(tool))));

        assertThatThrownBy(() -> connections.registerInto(new Toolkit()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("memory_save").hasMessageContaining("重名");
        connections.close();
    }

    @Test
    void 服务端没有返回工具清单时给出明确的错误_而不是空指针() {
        McpConnections connections = McpConnections.connect(List.of(cfg("empty")),
                s -> new FakeClient(s.name(), new ArrayList<>(), Mono.empty(), null, Mono.empty()));

        assertThatThrownBy(() -> connections.registerInto(new Toolkit()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("empty").hasMessageContaining("没有返回工具清单");
        connections.close();
    }

    @Test
    void 关闭按连接的相反顺序进行() {
        List<String> closed = new ArrayList<>();
        McpConnections connections = McpConnections.connect(List.of(cfg("a"), cfg("b"), cfg("c")),
                s -> new FakeClient(s.name(), closed, Mono.empty(), null));

        connections.close();

        assertThat(closed).containsExactly("c", "b", "a");
    }

    @Test
    void 某个连接关不掉不影响其余连接被关闭_且异常不被吞掉() {
        List<String> closed = new ArrayList<>();
        McpConnections connections = McpConnections.connect(List.of(cfg("a"), cfg("b"), cfg("c")),
                s -> new FakeClient(s.name(), closed, Mono.empty(),
                        s.name().equals("b") ? new IllegalStateException("b 关闭失败") : null));

        assertThatThrownBy(connections::close).hasMessage("b 关闭失败");

        assertThat(closed).containsExactly("c", "b", "a");
    }

    @Test
    void 建连失败时已连上的按相反顺序关闭_并以原始失败为主异常() {
        List<String> closed = new ArrayList<>();
        RuntimeException closeFailure = new IllegalStateException("b 关闭时也失败了");

        assertThatThrownBy(() -> McpConnections.connect(List.of(cfg("a"), cfg("b"), cfg("c")), s -> switch (s.name()) {
            case "a" -> new FakeClient("a", closed, Mono.empty(), null);
            case "b" -> new FakeClient("b", closed, Mono.empty(), closeFailure);
            default -> new FakeClient("c", closed, Mono.error(new IllegalStateException("连接被拒绝")), null);
        }))
                // 主异常是「c 连不上」，不是关闭 b 时的失败
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法连接 MCP 服务").hasMessageContaining("name=c")
                // 原异常不作为 cause 传出（消息里可能带着地址里的令牌），只在主异常消息里留类名
                .hasNoCause().hasMessageContaining("IllegalStateException").hasMessageNotContaining("连接被拒绝")
                // 关闭 b 的失败只作为 suppressed 挂着，没有掩盖主因
                .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(closeFailure));

        // 失败的 c 自己也要关（它可能已建立了一半的连接），然后 b、a
        assertThat(closed).containsExactly("c", "b", "a");
    }

    @Test
    void 连不上真实地址时启动失败_且错误信息不含地址里的令牌() {
        YokeProperties.McpServer unreachable = new YokeProperties.McpServer("bad", "streamable-http",
                "http://127.0.0.1:1/mcp?token=super-secret", Duration.ofSeconds(2), Map.of());

        assertThatThrownBy(() -> McpConnections.connect(List.of(unreachable)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法连接 MCP 服务").hasMessageContaining("name=bad")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("super-secret"));
    }
}
