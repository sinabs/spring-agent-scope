package com.yoke.a2a;

import java.time.Duration;
import java.util.UUID;

import com.yoke.support.A2aRaw;
import com.yoke.support.RecordingModel;
import com.yoke.support.RedisIntegrationTest;
import com.yoke.support.StubModelConfiguration;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 客户端经 Agent Card 发现 → message/send → 拿到回复，端到端：同一个 JVM 里 Yoke 自己既是服务端（带 token）
 * 又是客户端，客户端通过 {@code POST /api/chat/remote} 调用自己的 A2A 服务。模型是假的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@Import(StubModelConfiguration.class)
class RemoteAgentEndToEndTests extends RedisIntegrationTest {

    static final int PORT = A2aRaw.freePort();
    static final String TOKEN = "remote-e2e-token-0123456789";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("yoke.a2a.server.enabled", () -> "true");
        registry.add("yoke.a2a.server.token", () -> TOKEN);
        registry.add("yoke.a2a.server.public-url", () -> "http://127.0.0.1:" + PORT);
        registry.add("yoke.a2a.remote.url", () -> "http://127.0.0.1:" + PORT);
        registry.add("yoke.a2a.remote.token", () -> TOKEN);
    }

    @Autowired
    RecordingModel model;

    final A2aRaw http = new A2aRaw(PORT, null);

    private static String uniqueId() {
        return "remote-" + UUID.randomUUID();
    }

    @Test
    void 经AgentCard发现后调用远端并拿到回复且会话延续() {
        String session = uniqueId();

        A2aRaw.Response first = http.postJson("/api/chat/remote",
                "{\"sessionId\":\"" + session + "\",\"message\":\"远端第一句\"}");
        A2aRaw.Response second = http.postJson("/api/chat/remote",
                "{\"sessionId\":\"" + session + "\",\"message\":\"远端第二句\"}");

        assertThat(first.status()).as(first.body()).isEqualTo(200);
        assertThat(first.json().path("reply").asText()).isEqualTo("ok");
        assertThat(first.json().path("sessionId").asText()).isEqualTo(session);
        assertThat(second.json().path("reply").asText()).isEqualTo("ok");
        assertThat(model.lastCallContains("远端第一句")).as("sessionId 经 A2A 透传到远端，历史接上").isTrue();
    }

    @Test
    void 不传会话ID时新建并返回() {
        A2aRaw.Response response = http.postJson("/api/chat/remote", "{\"message\":\"没有会话\"}");

        assertThat(response.json().path("sessionId").asText()).isNotBlank();
    }

    @Test
    void 请求体不能指定远端地址也不能带非法会话ID() {
        // 多出来的字段被忽略，目标仍然只来自配置；非法 sessionId 与 /api/chat 一样 400
        A2aRaw.Response ignored = http.postJson("/api/chat/remote",
                "{\"message\":\"x\",\"url\":\"http://169.254.169.254/\",\"agentName\":\"evil\"}");
        A2aRaw.Response bad = http.postJson("/api/chat/remote", "{\"sessionId\":\"a/b\",\"message\":\"x\"}");
        A2aRaw.Response blank = http.postJson("/api/chat/remote", "{\"message\":\"  \"}");

        assertThat(ignored.status()).isEqualTo(200);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(blank.status()).isEqualTo(400);
    }

    @Test
    void token错误时调用失败并包成明确的异常而不是静默() {
        RemoteAgentClient wrongToken = new RemoteAgentClient(
                WellKnownAgentCardResolver.builder().baseUrl("http://127.0.0.1:" + PORT).build(),
                "remote", "wrong-token", Duration.ofSeconds(10), RemoteAgentClient.originOf("http://127.0.0.1:" + PORT));

        assertThatThrownBy(() -> wrongToken.ask("s-wrong-token", "你好").block())
                .isInstanceOf(RemoteAgentException.class)
                .hasMessageContaining("调用远端 Agent 失败")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("wrong-token").doesNotContain("127.0.0.1"));
    }

    @Test
    void 卡片声明的地址与配置不同源时拒绝调用() {
        // 配置写的是 localhost，卡片里写的是 127.0.0.1：同机但不同源，也要拒绝，否则改过的卡片能把请求引到别处
        RemoteAgentClient mismatched = new RemoteAgentClient(
                WellKnownAgentCardResolver.builder().baseUrl("http://127.0.0.1:" + PORT).build(),
                "remote", TOKEN, Duration.ofSeconds(10), RemoteAgentClient.originOf("http://localhost:" + PORT));
        int before = model.received.size();

        assertThatThrownBy(() -> mismatched.ask("s-origin", "你好").block())
                .isInstanceOf(RemoteAgentException.class)
                .hasMessageContaining("不一致");
        assertThat(model.received.size()).isEqualTo(before);
    }
}
