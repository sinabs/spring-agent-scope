package com.yoke.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

/**
 * 按脚本回复的假模型，不产生任何网络调用：每次调用把「消息 + 工具清单」交给脚本函数，脚本返回要回复的内容块。
 * 同时把每次调用原样记录下来，测试可以据此断言「模型看到了哪些工具、拿到了什么工具结果」。
 */
public class ScriptedModel implements Model {

    /** 一次模型调用的现场。 */
    public record Call(List<Msg> messages, List<ToolSchema> tools) {

        public List<String> toolNames() {
            return tools.stream().map(ToolSchema::getName).toList();
        }

        /** 第一条系统消息的文本，用来区分是主 Agent 还是哪个子 Agent 在调用。 */
        public String systemText() {
            return messages.stream().filter(m -> m.getRole() == MsgRole.SYSTEM).findFirst()
                    .map(Msg::getTextContent).orElse("");
        }

        /** 最后一个工具结果块的文本；没有则返回 null。 */
        public String lastToolResultText() {
            String found = null;
            for (Msg message : messages) {
                for (ContentBlock block : message.getContent()) {
                    if (block instanceof ToolResultBlock result) {
                        found = result.getOutput().stream()
                                .filter(TextBlock.class::isInstance).map(b -> ((TextBlock) b).getText())
                                .reduce("", String::concat);
                    }
                }
            }
            return found;
        }

        public boolean hasToolResult() {
            return lastToolResultText() != null;
        }
    }

    public final List<Call> calls = new CopyOnWriteArrayList<>();

    private final Function<Call, List<ContentBlock>> script;

    public ScriptedModel(Function<Call, List<ContentBlock>> script) {
        this.script = script;
    }

    public static List<ContentBlock> text(String text) {
        return List.of(TextBlock.builder().text(text).build());
    }

    public static List<ContentBlock> toolUse(String id, String name, Map<String, Object> input) {
        // 流式聚合时框架以 content 里的原始 JSON 为准解析入参，所以两处都要给
        try {
            String json = new ObjectMapper().writeValueAsString(input);
            return List.of(ToolUseBlock.builder().id(id).name(name).input(input).content(json).build());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Call call = new Call(List.copyOf(messages), tools == null ? List.of() : List.copyOf(tools));
        calls.add(call);
        return Flux.just(ChatResponse.builder().content(script.apply(call)).build());
    }

    @Override
    public String getModelName() {
        return "scripted";
    }
}
