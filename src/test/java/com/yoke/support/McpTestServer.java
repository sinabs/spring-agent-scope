package com.yoke.support;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

/**
 * 进程内的最小 MCP 服务：用 MCP 官方 SDK 的 Streamable HTTP 服务端传输挂在嵌入式 Tomcat 上（两者都已在类路径里，
 * 不引入新依赖），公布 {@code echo}（只读）和 {@code write_note}（未声明只读）两个工具；也可以起成旧的 SSE 传输。测试里的客户端是真实的 McpClientBuilder，走完整的握手、列工具、调用流程。
 */
public final class McpTestServer implements AutoCloseable {

    public static final String PATH = "/mcp";

    public enum Transport { STREAMABLE_HTTP, SSE }

    /** 服务端收到的请求头（小写名到值），用来证明配置里的请求头确实被带上了。 */
    public final List<Map<String, String>> receivedHeaders = new CopyOnWriteArrayList<>();

    private final Tomcat tomcat;
    private final McpSyncServer server;
    private final int port;

    private final Transport transport;

    public McpTestServer() {
        this(Transport.STREAMABLE_HTTP);
    }

    public McpTestServer(Transport transport) {
        this.transport = transport;
        try {
            port = freePort();
            File base = Files.createTempDirectory("mcp-test-tomcat").toFile();
            tomcat = new Tomcat();
            tomcat.setBaseDir(base.getAbsolutePath());
            tomcat.setPort(port);
            tomcat.getConnector();

            jakarta.servlet.http.HttpServlet servlet;
            McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder().tools(false).build();
            if (transport == Transport.SSE) {
                HttpServletSseServerTransportProvider provider = HttpServletSseServerTransportProvider.builder()
                        .jsonMapper(McpJsonMapper.getDefault())
                        // 不设 baseUrl：服务端通告相对路径的消息端点。AgentScope 把完整的 SSE 地址当作客户端的 baseUri，
                        // 服务端若通告绝对地址（http://主机:端口/...），会因为「与 baseUri 不匹配」而被客户端拒绝
                        .sseEndpoint("/sse")
                        .messageEndpoint("/mcp/message")
                        .build();
                server = McpServer.sync(provider).serverInfo("yoke-test-mcp", "1.0.0")
                        .capabilities(capabilities).tools(echoTool(), writeNoteTool()).build();
                servlet = provider;
            } else {
                HttpServletStreamableServerTransportProvider provider = HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(McpJsonMapper.getDefault())
                        .mcpEndpoint(PATH)
                        .build();
                server = McpServer.sync(provider).serverInfo("yoke-test-mcp", "1.0.0")
                        .capabilities(capabilities).tools(echoTool(), writeNoteTool()).build();
                servlet = provider;
            }

            Context context = tomcat.addContext("", base.getAbsolutePath());
            Tomcat.addServlet(context, "mcp", servlet).setAsyncSupported(true);
            context.addServletMappingDecoded("/*", "mcp");
            context.addFilterDef(filterDef());
            context.addFilterMap(filterMap());
            tomcat.start();
        } catch (Exception e) {
            throw new IllegalStateException("启动测试用 MCP 服务失败", e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + port + (transport == Transport.SSE ? "/sse" : PATH);
    }

    /** {@code write_note} 被真正执行的次数；它没有声明只读，用来证明需要授权的 MCP 工具不会被悄悄执行。 */
    public final AtomicInteger writeNoteCalls = new AtomicInteger();

    private McpServerFeatures.SyncToolSpecification writeNoteTool() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
                Map.of("text", Map.of("type", "string")), List.of("text"), null, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("write_note")
                .description("写一条笔记（有副作用，没有声明只读）")
                .inputSchema(schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    writeNoteCalls.incrementAndGet();
                    return new McpSchema.CallToolResult("已写入", false);
                })
                .build();
    }

    private static McpServerFeatures.SyncToolSpecification echoTool() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
                Map.of("text", Map.of("type", "string", "description", "要回显的文字")), List.of("text"), null, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("echo")
                .description("原样回显收到的文字，用来验证 MCP 连通")
                .inputSchema(schema)
                // 声明只读：2.0.4 对只读的 MCP 工具自动放行，其余都要求显式授权
                .annotations(new McpSchema.ToolAnnotations("echo", true, false, true, false, false))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> new McpSchema.CallToolResult(
                        "echo: " + request.arguments().get("text"), false))
                .build();
    }

    private org.apache.tomcat.util.descriptor.web.FilterDef filterDef() {
        org.apache.tomcat.util.descriptor.web.FilterDef def = new org.apache.tomcat.util.descriptor.web.FilterDef();
        def.setFilterName("headers");
        def.setFilter((jakarta.servlet.Filter) (request, response, chain) -> {
            // JSON-RPC 请求体按 UTF-8；Servlet 在 Content-Type 不带 charset 时默认按 ISO-8859-1 解码，中文会乱码
            request.setCharacterEncoding("UTF-8");
            if (request instanceof HttpServletRequest http) {
                Map<String, String> headers = new java.util.HashMap<>();
                http.getHeaderNames().asIterator().forEachRemaining(name -> headers.put(name.toLowerCase(), http.getHeader(name)));
                receivedHeaders.add(headers);
            }
            chain.doFilter(request, response);
        });
        return def;
    }

    private static org.apache.tomcat.util.descriptor.web.FilterMap filterMap() {
        org.apache.tomcat.util.descriptor.web.FilterMap map = new org.apache.tomcat.util.descriptor.web.FilterMap();
        map.setFilterName("headers");
        map.addURLPattern("/*");
        return map;
    }

    @Override
    public void close() {
        try {
            server.closeGracefully();
            tomcat.stop();
            tomcat.destroy();
        } catch (Exception e) {
            throw new IllegalStateException("关闭测试用 MCP 服务失败", e);
        }
    }

    private static int freePort() throws java.io.IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
