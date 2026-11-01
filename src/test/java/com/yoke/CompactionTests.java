package com.yoke;

import java.util.UUID;

import com.yoke.controller.ChatController;
import com.yoke.model.ChatRequest;
import com.yoke.support.RecordingModel;
import com.yoke.support.RedisIntegrationTest;
import com.yoke.support.StubModelConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把触发条数调小，验证配置真的传到了 HarnessAgent：对话变长后会多出一次摘要调用，
 * 之后喂给模型的历史被压短。摘要调用也走假模型，所以能直接在它收到的消息里看到摘要提示词。
 */
@SpringBootTest(properties = {"yoke.compaction.trigger-messages=6", "yoke.compaction.keep-messages=2"})
@ActiveProfiles("test")
@Import(StubModelConfiguration.class)
class CompactionTests extends RedisIntegrationTest {

    @Autowired
    ChatController controller;

    @Autowired
    RecordingModel model;

    @Test
    void 对话达到触发条数后被压缩() {
        String session = "compaction-" + UUID.randomUUID();
        int before = model.received.size();

        for (int round = 1; round <= 6; round++) {
            controller.chat(new ChatRequest(session, "第" + round + "轮提问")).block();
        }

        var calls = model.received.subList(before, model.received.size());
        assertThat(calls).as("出现过摘要调用").anyMatch(messages -> messages.stream()
                .anyMatch(msg -> msg.getTextContent() != null && msg.getTextContent().contains("SESSION INTENT")));
    }

    /**
     * 取某一轮推理调用的消息数。回复之后框架还会异步发起记忆整理这类不带工具的调用，
     * 所以只看带工具清单的那次（receivedTools 与 received 按调用顺序一一对应）。
     */
    private int reasoningSize(int from, String text) {
        int size = -1;
        for (int i = from; i < model.received.size(); i++) {
            var messages = model.received.get(i);
            boolean mentionsText = messages.stream().anyMatch(m -> m.getTextContent() != null && m.getTextContent().contains(text));
            if (mentionsText && !model.receivedTools.get(i).isEmpty()) {
                size = messages.size();
            }
        }
        return size;
    }

    @Test
    void 压缩后的推理输入会变短而不是一直增长() {
        String session = "compaction-len-" + UUID.randomUUID();
        java.util.List<Integer> sizes = new java.util.ArrayList<>();

        for (int round = 1; round <= 8; round++) {
            int before = model.received.size();
            controller.chat(new ChatRequest(session, "第" + round + "轮提问")).block();
            sizes.add(reasoningSize(before, "第" + round + "轮提问"));
        }

        // 不压缩时每一轮的输入都比上一轮长；压缩发生后最后一轮应比之前出现过的最大值短
        assertThat(sizes.getLast()).as("各轮推理输入的消息数 %s", sizes).isLessThan(java.util.Collections.max(sizes));
    }
}
