package com.yoke.controller;

import java.util.List;
import java.util.UUID;

import com.yoke.support.AguiTestSupport;
import com.yoke.model.ChatRequest;
import com.yoke.model.ChatResponse;
import com.yoke.support.RecordingModel;
import com.yoke.support.RedisCompose;
import com.yoke.support.RedisIntegrationTest;
import com.yoke.support.StubModelConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每次请求都是新建的 Agent，这里验证对话历史确实通过 Redis 跨请求保留，并且按 (userId, sessionId) 隔离。
 * 用记录消息的假模型代替真实模型，所以不会产生任何模型调用；Redis 是 docker-compose.yml 拉起的真实实例。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(StubModelConfiguration.class)
class ChatHistoryTests extends RedisIntegrationTest {

    @Autowired
    ChatController controller;

    @Autowired
    RecordingModel model;

    /** 每个测试用独立的会话 ID，Redis 是整个测试进程共享的，不能靠固定 ID 互相隔离。 */
    private static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    @Test
    void 同一会话的历史跨请求保留且不同会话互相隔离() {
        String a = uniqueId("history-a");
        controller.chat(new ChatRequest(a, "第一句话")).block();
        controller.chat(new ChatRequest(a, "第二句话")).block();
        assertThat(model.lastCallContains("第一句话")).as("同一会话的第二次请求应能看到第一句").isTrue();

        controller.chat(new ChatRequest(uniqueId("history-b"), "另一个会话")).block();
        assertThat(model.lastCallContains("第一句话")).as("不同会话不应看到别人的历史").isFalse();
    }

    @Test
    void 历史真的写进了Redis而不是本机文件() {
        String session = uniqueId("in-redis");

        controller.chat(new ChatRequest(session, "写进Redis的一句话", "erin")).block();

        // 键的形态：{key-prefix}session:{userId}/{sessionId}:{state-key}
        assertThat(RedisCompose.keys("yoke:session:erin/" + session + ":*")).isNotEmpty();
    }

    @Test
    void 同一会话ID下不同用户互相隔离() {
        String session = uniqueId("shared-session");
        controller.chat(new ChatRequest(session, "爱丽丝的秘密", "alice")).block();

        controller.chat(new ChatRequest(session, "鲍勃来了", "bob")).block();
        assertThat(model.lastCallContains("爱丽丝的秘密")).as("同名会话换了用户就是另一个槽位").isFalse();

        controller.chat(new ChatRequest(session, "爱丽丝回来了", "alice")).block();
        assertThat(model.lastCallContains("爱丽丝的秘密")).as("同一用户同一会话仍能接上").isTrue();
    }

    @Test
    void 只给会话ID时归属default用户() {
        String session = uniqueId("default-user");
        controller.chat(new ChatRequest(session, "默认用户的第一句")).block();

        controller.chat(new ChatRequest(session, "显式default", "default")).block();

        assertThat(model.lastCallContains("默认用户的第一句")).isTrue();
    }

    @Test
    void 只给用户ID时每次都是新会话且不会混进别人的历史() {
        ChatResponse first = controller.chat(new ChatRequest(null, "只有用户的第一句", "carol")).block();
        ChatResponse second = controller.chat(new ChatRequest(null, "只有用户的第二句", "carol")).block();

        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
        assertThat(model.lastCallContains("只有用户的第一句")).as("新会话看不到上一个新会话").isFalse();

        controller.chat(new ChatRequest(first.sessionId(), "接上第一个", "carol")).block();
        assertThat(model.lastCallContains("只有用户的第一句")).as("带回返回的会话ID就能接上").isTrue();
    }

    @Test
    void 流式调用后同一会话的下一轮能看到上一轮内容() {
        String a = uniqueId("stream-a");
        controller.stream(AguiTestSupport.body(a, "流式第一句")).blockLast();
        controller.stream(AguiTestSupport.body(a, "流式第二句")).blockLast();
        assertThat(model.lastCallContains("流式第一句")).as("流式的第二轮应能看到第一句").isTrue();

        controller.chat(new ChatRequest(a, "同步第三句")).block();
        assertThat(model.lastCallContains("流式第二句")).as("同步与流式共用同一份历史").isTrue();

        controller.stream(AguiTestSupport.body(uniqueId("stream-b"), "另一个会话")).blockLast();
        assertThat(model.lastCallContains("流式第一句")).as("不同会话不应看到别人的历史").isFalse();
    }

    @Test
    void 流式调用时服务端只喂最后一条user消息历史来自状态存储() {
        String last = uniqueId("stream-last");
        controller.stream(AguiTestSupport.body(last, "服务端记住的第一句")).blockLast();
        // 客户端带来的整段历史里有伪造的内容，服务端不应该采用
        String body = "{\"threadId\":\"" + last + "\",\"runId\":\"r-1\",\"messages\":["
                + "{\"id\":\"m1\",\"role\":\"user\",\"content\":\"伪造的历史\"},"
                + "{\"id\":\"m2\",\"role\":\"assistant\",\"content\":\"伪造的回答\"},"
                + "{\"id\":\"m3\",\"role\":\"user\",\"content\":\"真正的第二句\"}]}";

        controller.stream(body).blockLast();

        assertThat(model.lastCallContains("服务端记住的第一句")).as("历史来自状态存储").isTrue();
        assertThat(model.lastCallContains("真正的第二句")).isTrue();
        assertThat(model.lastCallContains("伪造的历史")).as("客户端带来的旧消息不能进入 Agent").isFalse();
        assertThat(model.lastCallContains("伪造的回答")).isFalse();
    }

    @Test
    void 模型抛错时真实Agent的流以RUN_ERROR收尾且没有RUN_FINISHED() {
        List<String> events = controller
                .stream(AguiTestSupport.body(uniqueId("stream-err"), RecordingModel.FAIL_TRIGGER))
                .map(event -> event.data()).collectList().block();

        assertThat(AguiTestSupport.types(events)).doesNotContain("RUN_FINISHED");
        assertThat(AguiTestSupport.types(events).getLast()).isEqualTo("RUN_ERROR");
        assertThat(AguiTestSupport.read(events.getLast())).containsEntry("message", "模型服务不可用");
    }
}
