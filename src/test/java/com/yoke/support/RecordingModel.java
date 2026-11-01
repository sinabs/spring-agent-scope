package com.yoke.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

/** 记录每次收到的消息和工具清单，并固定回复 ok 的假模型，不产生任何网络调用。 */
public class RecordingModel implements Model {

    /** 输入里出现这个文字时模型抛错，用来走真实 Agent 的出错路径。 */
    public static final String FAIL_TRIGGER = "触发模型错误";

    public final List<List<Msg>> received = new CopyOnWriteArrayList<>();

    public final List<List<String>> receivedTools = new CopyOnWriteArrayList<>();

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        received.add(List.copyOf(messages));
        receivedTools.add(tools == null ? List.of() : tools.stream().map(ToolSchema::getName).toList());
        if (messages.stream().anyMatch(msg -> msg.getTextContent() != null && msg.getTextContent().contains(FAIL_TRIGGER))) {
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

    /** 最近一次调用的输入里有没有出现某段文字。 */
    public boolean lastCallContains(String text) {
        return received.getLast().stream()
                .anyMatch(msg -> msg.getTextContent() != null && msg.getTextContent().contains(text));
    }
}
