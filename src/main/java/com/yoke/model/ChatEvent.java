package com.yoke.model;

/**
 * 流式对话对外推送的事件，是一个封闭集合：调用方只会收到下面这几种，新增种类必须修改这里。
 *
 * <p>这些类型只用 JDK 类型表达，不暴露 AgentScope 的任何类，框架升级时对外协议不受影响。
 * 框架把一次工具调用拆成 start、delta、end 多个细粒度事件，这里已经攒成一个完整事件再推送。
 */
public sealed interface ChatEvent {

    /**
     * SSE 的 event 名，与事件类型一一对应。
     */
    default String eventName() {
        return switch (this) {
            case TextDelta ignored -> "textDelta";
            case ToolCall ignored -> "toolCall";
            case ToolResult ignored -> "toolResult";
            case Hint ignored -> "hint";
            case Done ignored -> "done";
            case Error ignored -> "error";
        };
    }

    /**
     * 模型生成的一段文本增量，按到达顺序拼接即得到回复。
     *
     * @param text 本次增量的文本
     */
    record TextDelta(String text) implements ChatEvent {
    }

    /**
     * 模型决定调用的一个工具，参数已经攒齐。
     *
     * @param id        工具调用 ID，与对应的 {@link ToolResult} 相同
     * @param name      工具名称
     * @param arguments 工具入参，JSON 文本；模型没有给参数时为 {@code {}}
     */
    record ToolCall(String id, String name, String arguments) implements ChatEvent {
    }

    /**
     * 一个工具的执行结果，输出已经攒齐。
     *
     * @param id     工具调用 ID，与对应的 {@link ToolCall} 相同
     * @param name   工具名称
     * @param state  执行状态，取值 success、error、interrupted、denied、running
     * @param output 工具返回值序列化后的文本（返回字符串时带引号）；非文本输出（图片等）不包含在内
     */
    record ToolResult(String id, String name, String state, String output) implements ChatEvent {
    }

    /**
     * 框架在推理过程中追加的提示（例如迭代次数告警）。
     *
     * @param source 提示的来源
     * @param text   提示内容
     */
    record Hint(String source, String text) implements ChatEvent {
    }

    /**
     * 本轮对话正常结束，总是流里的最后一个事件。
     *
     * @param sessionId 本次对话所属的会话 ID
     * @param text      最终回复的完整文本，与同步接口返回的 reply 一致
     * @param usage     本轮所有模型调用累计的 token 用量
     */
    record Done(String sessionId, String text, Usage usage) implements ChatEvent {
    }

    /**
     * 本轮对话出错。出错后流正常结束而不是断开连接，所以它同样是最后一个事件。
     *
     * @param message 错误信息
     */
    record Error(String message) implements ChatEvent {
    }

    /**
     * token 用量。
     *
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     * @param totalTokens  合计
     */
    record Usage(int inputTokens, int outputTokens, int totalTokens) {
    }
}
