package com.yoke.knowledge.embed;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import com.yoke.knowledge.retrieve.VectorRetrievePort;
import com.yoke.model.Passage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用 JDK 自带的 HttpServer 模拟火山方舟的 /embeddings/multimodal 接口，不访问外网。 */
class ArkEmbeddingClientTests {

    private HttpServer server;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile int status = 200;
    private volatile String responseBody;
    private volatile boolean computeFromRequest;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 只注册多模态路径：客户端如果误请求标准的 /embeddings，服务端返回 404，测试会红
        server.createContext("/api/v3/embeddings/multimodal", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(request);
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = (computeFromRequest ? vectorFor(request) : responseBody).getBytes(StandardCharsets.UTF_8);
            inFlight.decrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ArkEmbeddingClient client(Integer dimensions) {
        RestClient rest = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3")
                .defaultHeader("Authorization", "Bearer ark-test")
                .build();
        return new ArkEmbeddingClient(rest, "doubao-embedding-vision-251215", dimensions);
    }

    @Test
    void 请求体按多模态协议组装_data是单个对象() {
        responseBody = """
                {"data":{"embedding":[0.25,0.75],"object":"embedding"},"model":"doubao-embedding-vision-251215"}""";

        List<double[]> vectors = client(2).embed(List.of("你好")).block();

        assertThat(vectors).hasSize(1);
        assertThat(vectors.getFirst()).containsExactly(0.25, 0.75);
        assertThat(requestBodies.getFirst()).contains("\"model\":\"doubao-embedding-vision-251215\"")
                .contains("\"input\":[{").contains("\"type\":\"text\"").contains("\"text\":\"你好\"")
                .contains("\"dimensions\":2");
        assertThat(authHeaders.getFirst()).isEqualTo("Bearer ark-test");
    }

    @Test
    void 兼容data是数组的响应() {
        responseBody = """
                {"data":[{"index":0,"embedding":[1.0,0.0]}]}""";

        assertThat(client(null).embed(List.of("x")).blockOptional().orElseThrow().getFirst()).containsExactly(1.0, 0.0);
    }

    @Test
    void 不指定维度时请求里不带dimensions() {
        responseBody = """
                {"data":{"embedding":[1.0]}}""";

        client(null).embed(List.of("x")).block();

        assertThat(requestBodies.getFirst()).doesNotContain("dimensions");
    }

    @Test
    void 不支持批量_多条文本拆成逐条请求且结果顺序与输入一致() {
        // 含「甲」的是 [1,0]，其余是 [0,1]；每条请求的 input 里只有一条文本
        computeFromRequest = true;

        List<double[]> vectors = client(null).embed(List.of("乙1", "甲2", "乙3", "甲4", "乙5")).block();

        assertThat(requestBodies).hasSize(5).allSatisfy(body -> assertThat(body).containsOnlyOnce("\"type\":\"text\""));
        assertThat(vectors).extracting(vector -> vector[0]).containsExactly(0.0, 1.0, 0.0, 1.0, 0.0);
    }

    @Test
    void 并发请求数不超过上限() {
        computeFromRequest = true;

        client(null).embed(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")).block();

        assertThat(maxInFlight.get()).isLessThanOrEqualTo(ArkEmbeddingClient.CONCURRENCY);
    }

    @Test
    void 返回的维度与配置不一致时报错() {
        responseBody = """
                {"data":{"embedding":[1.0,2.0,3.0]}}""";

        assertThatThrownBy(() -> client(2).embed(List.of("x")).block()).hasMessageContaining("不一致");
    }

    @Test
    void 响应里没有embedding时报错() {
        responseBody = """
                {"data":{}}""";

        assertThatThrownBy(() -> client(null).embed(List.of("x")).block()).hasMessageContaining("没有 embedding");
    }

    @Test
    void 上游返回错误状态码时报错且不泄漏密钥() {
        status = 429;
        responseBody = "{}";

        assertThatThrownBy(() -> client(null).embed(List.of("a")).block())
                .isInstanceOf(RuntimeException.class)
                .satisfies(error -> assertThat(String.valueOf(error.getMessage())).doesNotContain("ark-test"));
    }

    @Test
    void 向量检索端到端_经过真实HTTP调用的向量化() {
        computeFromRequest = true;
        List<Passage> passages = List.of(new Passage("a", "A", "甲方"), new Passage("b", "B", "乙方"));
        VectorRetrievePort port = new VectorRetrievePort(passages, client(null));

        assertThat(port.retrieve("甲", 1).block()).extracting(Passage::id).containsExactly("a");
        assertThat(port.retrieve("乙", 1).block()).extracting(Passage::id).containsExactly("b");
        // 建索引 2 条片段 + 两次查询各 1 条，不支持批量所以是 4 次请求
        assertThat(requestBodies).hasSize(4);
    }

    private static String vectorFor(String requestBody) {
        String vector = requestBody.contains("甲") ? "[1.0,0.0]" : "[0.0,1.0]";
        return "{\"data\":{\"embedding\":" + vector + "}}";
    }
}
