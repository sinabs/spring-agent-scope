package com.yoke.codec;

import java.util.List;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;

/**
 * 领域输入与框架消息 {@link Msg} 之间的转换。无状态，全部是静态方法。
 *
 * <p>同步和流式接口都从这里进出：Msg 只在 codec 内部出现，控制器和对外模型不接触它。
 */
public final class MessageCodec {

    private MessageCodec() {
    }

    /**
     * 把用户输入的文本转成喂给 Agent 的消息列表。
     */
    public static List<Msg> toInput(String text) {
        return List.of(new UserMessage(text));
    }

    /**
     * 取出 Agent 回复里的文本。
     */
    public static String toText(Msg reply) {
        return reply.getTextContent();
    }
}
