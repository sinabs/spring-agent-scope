package com.yoke.knowledge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用 JDK 自带的 HttpServer 模拟 OpenAI 兼容的 /embeddings 接口，不访问外网。 */
class OpenAiEmbeddingClientTests {

    private HttpServer server;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody;
    private volatile boolean computeFromRequest;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(request);
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (computeFromRequest ? vectorsFor(request) : responseBody).getBytes(StandardCharsets.UTF_8);
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

    private OpenAiEmbeddingClient client(Integer dimensions) {
        RestClient rest = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .defaultHeader("Authorization", "Bearer sk-test")
                .build();
        return new OpenAiEmbeddingClient(rest, "text-embedding-3-small", dimensions);
    }

    @Test
    void 请求体按OpenAI协议组装_返回按index排好序的向量() {
        // 故意乱序返回，客户端必须按 index 还原
        responseBody = """
                {"data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.5]}]}""";

        List<double[]> vectors = client(256).embed(List.of("第一段", "第二段")).block();

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(1.0, 0.5);
        assertThat(vectors.get(1)).containsExactly(0.0, 1.0);
        assertThat(requestBodies.getFirst()).contains("\"model\":\"text-embedding-3-small\"")
                .contains("\"input\":[\"第一段\",\"第二段\"]").contains("\"dimensions\":256")
                .contains("\"encoding_format\":\"float\"");
        assertThat(authHeaders.getFirst()).isEqualTo("Bearer sk-test");
    }

    @Test
    void 不指定维度时请求里不带dimensions() {
        responseBody = """
                {"data":[{"index":0,"embedding":[1.0]}]}""";

        client(null).embed(List.of("x")).block();

        assertThat(requestBodies.getFirst()).doesNotContain("dimensions");
    }

    @Test
    void 返回条数与请求不一致时报错() {
        responseBody = """
                {"data":[{"index":0,"embedding":[1.0]}]}""";

        assertThatThrownBy(() -> client(null).embed(List.of("a", "b")).block()).hasMessageContaining("不一致");
    }

    @Test
    void 上游返回错误状态码时报错() {
        status = 503;
        responseBody = "{}";

        assertThatThrownBy(() -> client(null).embed(List.of("a")).block()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void 向量检索端到端_经过真实HTTP调用的向量化() {
        // 按请求里的文本现算向量：含「甲」的是 [1,0]，其余是 [0,1]
        computeFromRequest = true;
        List<Passage> passages = List.of(new Passage("a", "A", "甲方"), new Passage("b", "B", "乙方"));
        VectorRetrievePort port = new VectorRetrievePort(passages, client(null));

        assertThat(port.retrieve("甲", 1).block()).extracting(Passage::id).containsExactly("a");
        assertThat(port.retrieve("乙", 1).block()).extracting(Passage::id).containsExactly("b");
        // 1 次建索引 + 2 次查询
        assertThat(requestBodies).hasSize(3);
    }

    private static String vectorsFor(String requestBody) {
        try {
            var input = new com.fasterxml.jackson.databind.ObjectMapper().readTree(requestBody).get("input");
            StringBuilder data = new StringBuilder("{\"data\":[");
            for (int i = 0; i < input.size(); i++) {
                String vector = input.get(i).asText().contains("甲") ? "[1.0,0.0]" : "[0.0,1.0]";
                data.append(i == 0 ? "" : ",").append("{\"index\":").append(i).append(",\"embedding\":").append(vector).append("}");
            }
            return data.append("]}").toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
