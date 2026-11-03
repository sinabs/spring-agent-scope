package com.yoke.controller;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import com.yoke.support.AguiTestSupport;
import com.yoke.model.ChatRequest;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每次请求都是新建的 Agent，这里验证对话历史确实通过共享的状态存储跨请求保留，并且按会话隔离。
 * 用记录消息的假模型代替真实模型，所以不会产生任何网络调用。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatHistoryTests {

    /** 记录每次收到的消息，并固定回复 ok。 */
    static class RecordingModel implements Model {

        /** 输入里出现这个文字时模型抛错，用来走真实 Agent 的出错路径。 */
        static final String FAIL_TRIGGER = "触发模型错误";

        final List<List<Msg>> received = new CopyOnWriteArrayList<>();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            received.add(List.copyOf(messages));
            if (messages.stream().anyMatch(msg -> Objects.nonNull(msg.getTextContent()) && msg.getTextContent().contains(FAIL_TRIGGER))) {
                return Flux.error(new IllegalStateException("模型服务不可用"));
            }
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("ok").build()))
                    .build());
        }

        @Override
        public String getModelName() {
            return "recording";
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubModelConfiguration {

        @Bean
        RecordingModel recordingModel() {
            return new RecordingModel();
        }
    }

    @Autowired
    ChatController controller;

    @Autowired
    RecordingModel model;

    private boolean lastCallContains(String text) {
        List<Msg> last = model.received.getLast();
        return last.stream().anyMatch(msg -> Objects.nonNull(msg.getTextContent()) && msg.getTextContent().contains(text));
    }

    @Test
    void 同一会话的历史跨请求保留且不同会话互相隔离() {
        controller.chat(new ChatRequest("history-a", "第一句话")).block();
        controller.chat(new ChatRequest("history-a", "第二句话")).block();
        assertThat(lastCallContains("第一句话")).as("同一会话的第二次请求应能看到第一句").isTrue();

        controller.chat(new ChatRequest("history-b", "另一个会话")).block();
        assertThat(lastCallContains("第一句话")).as("不同会话不应看到别人的历史").isFalse();
    }

    @Test
    void 流式调用后同一会话的下一轮能看到上一轮内容() {
        controller.stream(AguiTestSupport.body("stream-a", "流式第一句")).blockLast();
        controller.stream(AguiTestSupport.body("stream-a", "流式第二句")).blockLast();
        assertThat(lastCallContains("流式第一句")).as("流式的第二轮应能看到第一句").isTrue();

        controller.chat(new ChatRequest("stream-a", "同步第三句")).block();
        assertThat(lastCallContains("流式第二句")).as("同步与流式共用同一份历史").isTrue();

        controller.stream(AguiTestSupport.body("stream-b", "另一个会话")).blockLast();
        assertThat(lastCallContains("流式第一句")).as("不同会话不应看到别人的历史").isFalse();
    }

    @Test
    void 流式调用时服务端只喂最后一条user消息历史来自状态存储() {
        controller.stream(AguiTestSupport.body("stream-last", "服务端记住的第一句")).blockLast();
        // 客户端带来的整段历史里有伪造的内容，服务端不应该采用
        String body = "{\"threadId\":\"stream-last\",\"messages\":["
                + "{\"id\":\"m1\",\"role\":\"user\",\"content\":\"伪造的历史\"},"
                + "{\"id\":\"m2\",\"role\":\"assistant\",\"content\":\"伪造的回答\"},"
                + "{\"id\":\"m3\",\"role\":\"user\",\"content\":\"真正的第二句\"}]}";

        controller.stream(body).blockLast();

        assertThat(lastCallContains("服务端记住的第一句")).as("历史来自状态存储").isTrue();
        assertThat(lastCallContains("真正的第二句")).isTrue();
        assertThat(lastCallContains("伪造的历史")).as("客户端带来的旧消息不能进入 Agent").isFalse();
        assertThat(lastCallContains("伪造的回答")).isFalse();
    }

    @Test
    void 模型抛错时真实Agent的流以RUN_ERROR收尾且没有RUN_FINISHED() {
        List<String> events = controller
                .stream(AguiTestSupport.body("stream-err", RecordingModel.FAIL_TRIGGER))
                .map(event -> event.data()).collectList().block();

        assertThat(AguiTestSupport.types(events)).doesNotContain("RUN_FINISHED");
        assertThat(AguiTestSupport.types(events).getLast()).isEqualTo("RUN_ERROR");
        assertThat(AguiTestSupport.read(events.getLast())).containsEntry("message", "模型服务不可用");
    }
}
