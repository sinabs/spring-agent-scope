package com.yoke.codec;

import java.util.Optional;
import java.util.Set;

import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.HintBlockEvent;

/**
 * 把框架追加的提示（例如迭代次数告警）转成 AG-UI 的 {@code CUSTOM} 事件，名称固定为 {@code hint}。
 *
 * <p>不注册的话，适配器会把 {@link HintBlockEvent} 落到 {@code RAW} 事件上。无状态，可被所有请求共享。
 */
public final class HintEventConverter implements AgentEventConverter {

    /** CUSTOM 事件的 name，对外协议的一部分。 */
    public static final String EVENT_NAME = "hint";

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(HintBlockEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        HintBlockEvent hint = (HintBlockEvent) event;
        // 提示来源和内容缺失时按空串处理，保证对外字段不为 null
        HintPayload value = new HintPayload(
                Optional.ofNullable(hint.getHintSource()).orElse(""),
                Optional.ofNullable(hint.getHint()).orElse(""));
        context.emit(new AguiEvent.Custom(context.getThreadId(), context.getRunId(), EVENT_NAME, value));
    }

    /** hint 事件的 value，字段名即对外 JSON 的字段名。 */
    public record HintPayload(String source, String text) {
    }
}
