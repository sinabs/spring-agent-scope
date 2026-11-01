package com.yoke.a2a;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.util.Utils;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 客户端的超时和错误包装：用一个手写的假远端（JDK HttpServer），不依赖 Docker 和 Spring。 */
class RemoteAgentClientTests {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (trap != null) {
            trap.stop(0);
        }
    }

    /** 起一个假远端：卡片正常，/a2a 按 handler 的行为响应。 */
    private String startRemote(long delayMillis, int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AgentCard card = new AgentCard.Builder().name("fake").description("假远端").url(base + "/a2a")
                .version("1.0.0").protocolVersion("0.3.0").preferredTransport("JSONRPC")
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of()).build();
        String cardJson = Utils.toJsonString(card);
        server.createContext("/.well-known/agent-card.json", exchange -> write(exchange, 200, cardJson));
        server.createContext("/a2a", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            write(exchange, status, body);
        });
        server.start();
        return base;
    }

    /** 陷阱服务端：记录收到的请求数和 Authorization 头，任何请求到了这里都说明请求被引到了别处。 */
    private HttpServer trap;
    private final java.util.concurrent.atomic.AtomicInteger trapHits = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.List<String> trapAuthHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger remoteRpcHits = new java.util.concurrent.atomic.AtomicInteger();

    private String startTrap() throws IOException {
        trap = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        trap.createContext("/", exchange -> {
            trapHits.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                trapAuthHeaders.add(auth);
            }
            exchange.getRequestBody().readAllBytes();
            write(exchange, 200, "{}");
        });
        trap.start();
        return "http://127.0.0.1:" + trap.getAddress().getPort();
    }

    /** 起一个假远端，卡片由调用方决定（url、首选传输、additionalInterfaces），/a2a 只数请求数。 */
    private String startRemoteWithCard(java.util.function.Function<String, AgentCard> cardOf) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String cardJson = Utils.toJsonString(cardOf.apply(base));
        server.createContext("/.well-known/agent-card.json", exchange -> write(exchange, 200, cardJson));
        server.createContext("/a2a", exchange -> {
            remoteRpcHits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            write(exchange, 500, "{}");
        });
        server.start();
        return base;
    }

    private static AgentCard card(String url, String preferred, List<io.a2a.spec.AgentInterface> additional) {
        return new AgentCard.Builder().name("fake").description("d").url(url).version("1.0.0").protocolVersion("0.3.0")
                .preferredTransport(preferred).additionalInterfaces(additional)
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of()).build();
    }

    private void assertRefusedWithoutAnyTraffic(String base) {
        RemoteAgentClient client = new RemoteAgentClient(WellKnownAgentCardResolver.builder().baseUrl(base).build(),
                "remote", "secret-token-to-protect", Duration.ofSeconds(5), RemoteAgentClient.originOf(base));

        assertThatThrownBy(() -> client.ask("s-trap", "你好").block())
                .isInstanceOf(RemoteAgentException.class).hasMessageContaining("不一致");
        assertThat(trapHits.get()).as("陷阱收到的请求数").isZero();
        assertThat(trapAuthHeaders).as("token 不能被发往别处").isEmpty();
        assertThat(remoteRpcHits.get()).as("远端 /a2a 也不应被调用").isZero();
    }

    @Test
    void url同源但首选传输非JSONRPC_additionalInterfaces指向别处时拒绝() throws IOException {
        String trapBase = startTrap();
        String base = startRemoteWithCard(self -> card(self + "/a2a", "GRPC",
                List.of(new io.a2a.spec.AgentInterface("JSONRPC", trapBase + "/a2a"))));

        assertRefusedWithoutAnyTraffic(base);
    }

    @Test
    void 首选传输是JSONRPC但additionalInterfaces里有别处的地址也拒绝() throws IOException {
        String trapBase = startTrap();
        String base = startRemoteWithCard(self -> card(self + "/a2a", "JSONRPC",
                List.of(new io.a2a.spec.AgentInterface("JSONRPC", trapBase + "/a2a"))));

        assertRefusedWithoutAnyTraffic(base);
    }

    @Test
    void additionalInterfaces指向同主机不同端口也拒绝() throws IOException {
        String trapBase = startTrap();
        String base = startRemoteWithCard(self -> card(self + "/a2a", "JSONRPC",
                List.of(new io.a2a.spec.AgentInterface("HTTP+JSON", trapBase + "/x"))));

        assertRefusedWithoutAnyTraffic(base);
    }

    @Test
    void 首选传输不是JSONRPC时拒绝() throws IOException {
        startTrap();
        String base = startRemoteWithCard(self -> card(self + "/a2a", "GRPC", List.of()));

        assertRefusedWithoutAnyTraffic(base);
    }

    @Test
    void 远端把消息接口重定向到别处时token不会被带过去() throws IOException {
        String trapBase = startTrap();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String cardJson = Utils.toJsonString(card(base + "/a2a", "JSONRPC", List.of()));
        server.createContext("/.well-known/agent-card.json", exchange -> write(exchange, 200, cardJson));
        server.createContext("/a2a", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", trapBase + "/stolen");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.start();
        RemoteAgentClient client = new RemoteAgentClient(WellKnownAgentCardResolver.builder().baseUrl(base).build(),
                "remote", "secret-token-to-protect", Duration.ofSeconds(5), RemoteAgentClient.originOf(base));

        assertThatThrownBy(() -> client.ask("s-redirect", "你好").block()).isInstanceOf(RemoteAgentException.class);
        assertThat(trapAuthHeaders).as("重定向后 token 不能出现在别的主机上").isEmpty();
        assertThat(trapHits.get()).as("不应跟随重定向到别的主机").isZero();
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static RemoteAgentClient clientFor(String base, Duration timeout) {
        return new RemoteAgentClient(WellKnownAgentCardResolver.builder().baseUrl(base).build(), "remote", null,
                timeout, RemoteAgentClient.originOf(base));
    }

    @Test
    void 远端响应太慢时超时并包成504异常() throws IOException {
        String base = startRemote(5_000, 200, "{}");

        assertThatThrownBy(() -> clientFor(base, Duration.ofSeconds(1)).ask("s-slow", "你好").block())
                .isInstanceOf(RemoteAgentException.class)
                .hasMessageContaining("超时")
                .satisfies(e -> assertThat(((RemoteAgentException) e).getStatusCode().value()).isEqualTo(504));
    }

    @Test
    void 远端返回错误时包成502异常且消息不含地址和对方原文() throws IOException {
        String base = startRemote(0, 500, "{\"secret\":\"内部堆栈 NullPointerException at com.remote\"}");

        assertThatThrownBy(() -> clientFor(base, Duration.ofSeconds(10)).ask("s-500", "你好").block())
                .isInstanceOf(RemoteAgentException.class)
                .satisfies(e -> {
                    assertThat(((RemoteAgentException) e).getStatusCode().value()).isEqualTo(502);
                    assertThat(e.getMessage()).doesNotContain("127.0.0.1").doesNotContain("内部堆栈");
                });
    }

    @Test
    void 远端返回JSON_RPC错误时同样是明确的异常() throws IOException {
        String rpcError = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32603,\"message\":\"智能体执行失败\"}}";
        String base = startRemote(0, 200, rpcError);

        assertThatThrownBy(() -> clientFor(base, Duration.ofSeconds(10)).ask("s-rpc", "你好").block())
                .isInstanceOf(RemoteAgentException.class);
    }

    @Test
    void 远端不可达时取卡片失败也是明确的异常() {
        RemoteAgentClient client = clientFor("http://127.0.0.1:1", Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.ask("s-down", "你好").block()).isInstanceOf(RemoteAgentException.class);
    }

    @Test
    void 同源判断补全默认端口并忽略大小写() {
        assertThat(RemoteAgentClient.originOf("https://Example.com/a2a")).isEqualTo("https://example.com:443");
        assertThat(RemoteAgentClient.originOf("http://example.com")).isEqualTo("http://example.com:80");
        assertThat(RemoteAgentClient.originOf("http://example.com:8080/x")).isNotEqualTo(RemoteAgentClient.originOf("http://example.com/x"));
        assertThat(Map.of("k", RemoteAgentClient.originOf(""))).containsEntry("k", "");
    }

    @Test
    void 取卡片的接口被重定向到别处时NoRedirectCardResolver拒绝跟随() throws IOException {
        String trapBase = startTrap();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/.well-known/agent-card.json", exchange -> {
            exchange.getResponseHeaders().add("Location", trapBase + "/.well-known/agent-card.json");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> new NoRedirectCardResolver(base, Duration.ofSeconds(5)).getAgentCard("remote"))
                .isInstanceOf(RuntimeException.class);
        assertThat(trapHits.get()).as("不应跟随重定向到别的主机").isZero();
    }

    @Test
    void 同源判断_IPv6_userinfo_默认端口() {
        assertThat(RemoteAgentClient.originOf("http://[::1]:8080/a2a")).isEqualTo("http://[::1]:8080");
        assertThat(RemoteAgentClient.originOf("http://[::1]/a2a")).isEqualTo("http://[::1]:80");
        // userinfo 不参与同源：http://good@evil 的主机是 evil，不是 good
        assertThat(RemoteAgentClient.originOf("http://good@evil.example/a2a")).isEqualTo("http://evil.example:80");
        assertThat(RemoteAgentClient.originOf("http://good@evil.example")).isNotEqualTo(RemoteAgentClient.originOf("http://good"));
        assertThat(RemoteAgentClient.originOf("http://example.com:80")).isEqualTo(RemoteAgentClient.originOf("http://example.com"));
        assertThat(RemoteAgentClient.originOf("https://example.com:443")).isEqualTo(RemoteAgentClient.originOf("https://example.com"));
        assertThat(RemoteAgentClient.originOf("http://example.com:8080")).isNotEqualTo(RemoteAgentClient.originOf("http://example.com"));
    }

    @Test
    void 解析不出主机的地址直接抛异常而不是变成null冒充的同源() {
        // 带下划线的主机名：URI 能解析，但 getHost() 是 null，两边都会变成 http://null:80
        assertThatThrownBy(() -> RemoteAgentClient.originOf("http://my_host:8080/a2a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RemoteAgentClient.originOf("/relative/path")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 卡片里的地址解析不出主机时拒绝调用() throws IOException {
        String trapBase = startTrap();
        String base = startRemoteWithCard(self -> card(self + "/a2a", "JSONRPC",
                List.of(new io.a2a.spec.AgentInterface("JSONRPC", "http://bad_host:" + trap.getAddress().getPort() + "/a2a"))));

        assertRefusedWithoutAnyTraffic(base);
    }
}
