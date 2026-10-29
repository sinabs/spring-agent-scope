package com.yoke.controller;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

        final List<List<Msg>> received = new CopyOnWriteArrayList<>();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            received.add(List.copyOf(messages));
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
        return last.stream().anyMatch(msg -> msg.getTextContent() != null && msg.getTextContent().contains(text));
    }

    @Test
    void 同一会话的历史跨请求保留且不同会话互相隔离() {
        controller.chat(new ChatRequest("history-a", "第一句话")).block();
        controller.chat(new ChatRequest("history-a", "第二句话")).block();
        assertThat(lastCallContains("第一句话")).as("同一会话的第二次请求应能看到第一句").isTrue();

        controller.chat(new ChatRequest("history-b", "另一个会话")).block();
        assertThat(lastCallContains("第一句话")).as("不同会话不应看到别人的历史").isFalse();
    }
}
