package com.yoke.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.yoke.config.YokeProperties;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 持有到外部 MCP 服务的长连接，负责建连和释放。
 *
 * <p>连接在应用启动时一次建好，所有请求共享（连接内部是线程安全的异步客户端）；工具注册到共享的 Toolkit 模板里，
 * 每个请求的 Toolkit 是模板的副本，副本不拥有连接，所以请求结束不会关掉它们。
 *
 * <p>生命周期约定：
 * <ul>
 *   <li>建连顺序即配置顺序；任何一个服务连不上，启动失败，并先把<b>已经连上的按相反顺序关闭</b>；</li>
 *   <li>关闭同样按相反顺序，每个连接各自 try/catch：一个关不掉不影响其余的；</li>
 *   <li>启动失败时关闭过程中的异常只作为 suppressed 挂到主异常上，<b>不会掩盖</b>真正的失败原因。</li>
 * </ul>
 *
 * <p>只支持 HTTP 传输，非法的传输方式在配置绑定阶段就已被拒绝，见 {@link YokeProperties.McpServer}。
 * 请求元数据（trace id、工具调用 id）不透传给 MCP 服务：它们不是完全可信的外部方。
 */
@Slf4j
public final class McpConnections implements AutoCloseable {

    /** 连接对象的创建方式，抽出来是为了让测试能注入失败的连接。 */
    @FunctionalInterface
    public interface ClientFactory extends Function<YokeProperties.McpServer, McpClientWrapper> {
    }

    /** 读取工具清单的等待上限：服务端卡住时不能让启动无限挂起。 */
    private static final Duration LIST_TOOLS_TIMEOUT = Duration.ofSeconds(10);

    private final List<McpClientWrapper> clients = new ArrayList<>();

    private McpConnections() {
    }

    /** 按配置连接所有服务；没有配置时返回一个空的连接集合。 */
    public static McpConnections connect(List<YokeProperties.McpServer> servers) {
        return connect(servers, McpConnections::buildClient);
    }

    public static McpConnections connect(List<YokeProperties.McpServer> servers, ClientFactory factory) {
        McpConnections connections = new McpConnections();
        for (YokeProperties.McpServer server : servers) {
            try {
                McpClientWrapper client = factory.apply(server);
                // initialize 会完成握手并缓存服务公布的工具清单；失败就让整个启动失败
                connections.clients.add(client);
                client.initialize().block(initTimeout(server));
                log.info("MCP 服务已连接：{}（{}）", server.name(), server.transport());
            } catch (RuntimeException e) {
                // 不把原异常作为 cause：它的消息里可能带着地址中的 token，只留类名
                RuntimeException primary = new IllegalStateException("无法连接 MCP 服务 " + server
                        + "，请确认服务已启动且地址、传输方式正确（原因：" + e.getClass().getSimpleName() + "）");
                connections.closeSuppressing(primary);
                throw primary;
            }
        }
        return connections;
    }

    /**
     * 把已连接服务的工具注册进 Toolkit，工具名就是服务端公布的原名。
     *
     * <p><b>只注册服务端声明了 {@code readOnlyHint} 的工具</b>，其余跳过，模型根本看不到它们。原因：AgentScope 对 MCP 工具
     * 只放行只读的，其余都要用户确认；HTTP 接口没有人工确认通道，把它们暴露给模型只会让一轮对话在工具调用处无声结束，
     * 调用方拿到空回复。与其这样，不如一开始就不暴露。跳过时只记工具名到 INFO 日志，不记参数。
     * 暴露的工具名若与 Toolkit 里已有的工具重名（例如内置的 {@code calculate}），启动失败，避免外部服务悄悄顶替内置工具。
     * 用 {@code disableTools} 而不是 {@code enableTools}：后者传空列表在 AgentScope 里表示「全部启用」。
     */
    public void registerInto(Toolkit toolkit) {
        for (McpClientWrapper client : clients) {
            List<McpSchema.Tool> tools = client.listTools().block(LIST_TOOLS_TIMEOUT);
            List<String> skipped = tools.stream().filter(McpConnections::notReadOnly).map(McpSchema.Tool::name).toList();
            tools.stream().map(McpSchema.Tool::name).filter(name -> !skipped.contains(name))
                    .filter(toolkit.getToolNames()::contains).findFirst().ifPresent(name -> {
                        throw new IllegalStateException("MCP 服务 " + client.getName() + " 的工具 " + name + " 与已有工具重名");
                    });
            if (!skipped.isEmpty()) {
                log.info("MCP 服务 {} 的以下工具未声明只读，不暴露给模型：{}", client.getName(), skipped);
            }
            toolkit.registration().mcpClient(client).disableTools(skipped).apply();
        }
    }

    private static boolean notReadOnly(McpSchema.Tool tool) {
        return Objects.isNull(tool.annotations()) || !Boolean.TRUE.equals(tool.annotations().readOnlyHint());
    }

    public List<String> names() {
        return clients.stream().map(McpClientWrapper::getName).toList();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (int i = clients.size() - 1; i >= 0; i--) {
            McpClientWrapper client = clients.get(i);
            try {
                client.close();
            } catch (RuntimeException e) {
                log.warn("关闭 MCP 连接失败：{}", client.getName(), e);
                if (Objects.isNull(failure)) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        clients.clear();
        if (Objects.nonNull(failure)) {
            throw failure;
        }
    }

    /** 建连失败后的清理：关闭异常只挂在主异常上，不抛出。 */
    private void closeSuppressing(RuntimeException primary) {
        try {
            close();
        } catch (RuntimeException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    private static Duration initTimeout(YokeProperties.McpServer server) {
        return server.timeout().plusSeconds(5);
    }

    private static McpClientWrapper buildClient(YokeProperties.McpServer server) {
        McpClientBuilder builder = McpClientBuilder.create(server.name())
                .timeout(server.timeout())
                .initializationTimeout(server.timeout())
                .propagateMeta(false);
        if (YokeProperties.McpServer.SSE.equals(server.transport())) {
            builder.sseTransport(server.url());
        } else {
            builder.streamableHttpTransport(server.url());
        }
        // 请求头只对 HTTP 传输生效，必须在选定传输之后设置，否则会被静默忽略
        builder.headers(server.headers());
        return Mono.defer(builder::buildAsync).block();
    }
}
