package com.yoke.support;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * 读取根目录的 docker-compose.yml 拉起 Nacos，整个测试 JVM 共用同一个实例。
 *
 * <p>Nacos 客户端的 gRPC 端口固定等于主端口 + 1000，所以不能让 Testcontainers 随机映射：
 * 这里挑一对都空闲的宿主机端口（P 和 P+1000）通过环境变量交给 compose。
 * 启动慢（要等控制台就绪），等待靠 compose 里的健康检查，最长 3 分钟，超时的报错里带上端口和排查提示。
 */
public final class NacosCompose {

    private static final String SERVICE = "nacos";

    private static ComposeContainer compose;
    private static String serverAddr;

    private NacosCompose() {
    }

    /** Nacos 的 {@code host:port}（主端口），形如 {@code 127.0.0.1:54321}。 */
    public static synchronized String serverAddr() {
        if (Objects.isNull(serverAddr)) {
            start();
        }
        return serverAddr;
    }

    /** 模拟运营在控制台发布一个新版本的 Prompt（管理接口，compose 里关了本地鉴权）。 */
    public static void publishPrompt(String key, String version, String template) {
        String form = "promptKey=" + enc(key) + "&version=" + enc(version) + "&commitMsg=test&template=" + enc(template);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + serverAddr() + "/nacos/v3/admin/ai/prompt"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).timeout(Duration.ofSeconds(10)).build();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (!response.body().contains("\"code\":0")) {
                throw new IllegalStateException("发布 Prompt 失败：" + response.body());
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("发布 Prompt 失败", e);
        }
    }

    /** 对 Nacos 主端口发一个 GET，返回响应体（管理接口，compose 里关了本地鉴权）。 */
    public static String httpGet(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + serverAddr() + path))
                .GET().timeout(Duration.ofSeconds(10)).build();
        try {
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("请求 Nacos 失败：" + path, e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void start() {
        int[] ports = freePortPair();
        int console = freePort();
        compose = new ComposeContainer(new File("docker-compose.yml"))
                .withEnv("NACOS_PORT", String.valueOf(ports[0]))
                .withEnv("NACOS_GRPC_PORT", String.valueOf(ports[1]))
                .withEnv("NACOS_CONSOLE_PORT", String.valueOf(console))
                .withServices(SERVICE)
                .withStartupTimeout(Duration.ofMinutes(3))
                .withExposedService(SERVICE, 8848, Wait.forHealthcheck());
        try {
            compose.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Nacos 容器没能在 3 分钟内就绪（主端口 " + ports[0] + "，gRPC 端口 " + ports[1]
                    + "）。检查 Docker 是否能拉取 docker-compose.yml 里的 nacos 镜像、这两个端口是否被占用", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(compose::stop));
        serverAddr = "127.0.0.1:" + ports[0];
    }

    /** 找一个主端口 P，使 P 和 P+1000 都空闲。P 在 20000 到 60000 之间随机挑，保证 P+1000 不越界。 */
    private static int[] freePortPair() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 200; attempt++) {
            int main = random.nextInt(20000, 60000);
            try (ServerSocket first = new ServerSocket(main); ServerSocket grpc = new ServerSocket(main + 1000)) {
                return new int[] {first.getLocalPort(), grpc.getLocalPort()};
            } catch (IOException ignored) {
                // 换一个再试
            }
        }
        throw new IllegalStateException("找不到一对相差 1000 的空闲端口");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("找不到空闲端口", e);
        }
    }
}
