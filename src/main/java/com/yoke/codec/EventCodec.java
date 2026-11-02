package com.yoke.codec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import cn.hutool.core.util.StrUtil;
import com.yoke.model.ChatEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.model.ChatUsage;

/**
 * 把框架拆得很细的 {@link AgentEvent} 流攒成对外的 {@link ChatEvent}。
 *
 * <p>框架把一次工具调用拆成 start、多个 delta、end：入参只出现在 delta 里，end 事件不带入参；
 * 工具名在 delta 上可能为空。所以这里按 toolCallId 缓存名称和碎片，等到 end 再产出完整事件。
 * 工具结果同理。用量分散在每次模型调用的结束事件上，这里负责累加。
 *
 * <p>有状态，非线程安全，<b>每个请求新建一个</b>，不能复用，否则缓存和用量会串到别的请求。
 * 框架保证同一个请求的事件按顺序逐个交付，所以单个实例内部不需要加锁。
 *
 * <p>思考过程、模型调用起止、人工确认等事件目前不对外暴露，直接忽略。
 */
public final class EventCodec {

    private final String sessionId;

    /** toolCallId 到工具名，调用与结果共用，用来回填缺失的名字。 */
    private final Map<String, String> toolNames = new HashMap<>();

    private final Map<String, StringBuilder> toolArguments = new HashMap<>();

    private final Map<String, StringBuilder> toolOutputs = new HashMap<>();

    private int inputTokens;

    private int outputTokens;

    private String replyText = "";

    public EventCodec(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 处理一个框架事件，返回需要对外推送的事件；碎片还没攒齐或不对外暴露时返回空列表。
     */
    public List<ChatEvent> decode(AgentEvent event) {
        return switch (event) {
            case TextBlockDeltaEvent e -> List.of(new ChatEvent.TextDelta(e.getDelta()));
            case ToolCallStartEvent e -> {
                rememberName(e.getToolCallId(), e.getToolCallName());
                toolArguments.put(e.getToolCallId(), new StringBuilder());
                yield List.of();
            }
            case ToolCallDeltaEvent e -> {
                rememberName(e.getToolCallId(), e.getToolCallName());
                toolArguments.computeIfAbsent(e.getToolCallId(), id -> new StringBuilder()).append(e.getDelta());
                yield List.of();
            }
            case ToolCallEndEvent e -> {
                rememberName(e.getToolCallId(), e.getToolCallName());
                StringBuilder arguments = toolArguments.remove(e.getToolCallId());
                String json = StrUtil.isBlank(arguments) ? "{}" : arguments.toString();
                yield List.of(new ChatEvent.ToolCall(e.getToolCallId(), nameOf(e.getToolCallId()), json));
            }
            case ToolResultStartEvent e -> {
                rememberName(e.getToolCallId(), e.getToolCallName());
                toolOutputs.put(e.getToolCallId(), new StringBuilder());
                yield List.of();
            }
            case ToolResultTextDeltaEvent e -> {
                rememberName(e.getToolCallId(), e.getToolCallName());
                toolOutputs.computeIfAbsent(e.getToolCallId(), id -> new StringBuilder()).append(e.getDelta());
                yield List.of();
            }
            case ToolResultEndEvent e -> {
                rememberName(e.getToolCallId(), e.getToolCallName());
                StringBuilder output = toolOutputs.remove(e.getToolCallId());
                String state = Objects.isNull(e.getState()) ? "success" : e.getState().getValue();
                yield List.of(new ChatEvent.ToolResult(e.getToolCallId(), nameOf(e.getToolCallId()), state,
                        Objects.isNull(output) ? "" : output.toString()));
            }
            case HintBlockEvent e -> List.of(new ChatEvent.Hint(e.getHintSource(), e.getHint()));
            case ModelCallEndEvent e -> {
                accumulate(e.getUsage());
                yield List.of();
            }
            case AgentResultEvent e -> {
                String text = Objects.isNull(e.getResult()) ? null : MessageCodec.toText(e.getResult());
                replyText = Objects.isNull(text) ? "" : text;
                yield List.of();
            }
            case AgentEndEvent ignored -> List.of(new ChatEvent.Done(sessionId, replyText,
                    new ChatEvent.Usage(inputTokens, outputTokens, inputTokens + outputTokens)));
            default -> List.of();
        };
    }

    private void rememberName(String toolCallId, String name) {
        if (StrUtil.isNotBlank(name)) {
            toolNames.put(toolCallId, name);
        }
    }

    private String nameOf(String toolCallId) {
        return toolNames.getOrDefault(toolCallId, "");
    }

    private void accumulate(ChatUsage usage) {
        if (Objects.nonNull(usage)) {
            inputTokens += usage.getInputTokens();
            outputTokens += usage.getOutputTokens();
        }
    }
}
