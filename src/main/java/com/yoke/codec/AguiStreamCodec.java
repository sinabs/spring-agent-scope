package com.yoke.codec;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import cn.hutool.core.util.StrUtil;
import com.yoke.model.SafeId;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.util.JsonUtils;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

/**
 * 流式接口与 AG-UI 协议之间的翻译层：解析并校验 {@code RunAgentInput} 请求体，把 Agent 的事件流编成 SSE 的 data 文本。
 *
 * <p>AG-UI 的请求与事件模型带的是 Jackson 2 注解，所以解析和序列化都交给 AgentScope 自带的 JSON 能力，
 * 不经过 Spring 的 Jackson 3 转换器；对外只暴露字符串。
 *
 * <p>无请求态字段，可被所有请求共享。
 */
@RequiredArgsConstructor
public final class AguiStreamCodec {

    /** RUN_ERROR 的兜底错误码。与适配器自己对未知异常使用的取值一致，库里没有公开这个常量。 */
    static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";

    @NonNull
    private final AguiAdapterConfig config;

    private final AguiEventEncoder encoder = new AguiEventEncoder();

    /**
     * 解析并校验请求体。
     *
     * <p>服务端只保留最后一条 user 消息：对话历史已经在状态存储里，适配器会把 {@code messages}
     * 全部转成 Agent 的输入，不能把客户端带来的整段历史再喂一遍。{@code tools}、{@code context}、
     * {@code state}、{@code forwardedProps}、{@code resume} 一律丢弃，客户端不能借此给 Agent 加工具。
     *
     * @throws InvalidStreamRequestException 请求体不是合法 JSON、缺少必要字段或 threadId 不合规
     */
    public StreamRequest parse(String body) {
        if (StrUtil.isBlank(body)) {
            throw new InvalidStreamRequestException("请求体不能为空");
        }
        RunAgentInput parsed = readInput(body);
        if (!SafeId.Validator.isSafe(parsed.getThreadId())) {
            throw new InvalidStreamRequestException("threadId 必须是 1~64 位的字母、数字或 _.-，且不能全是点");
        }
        if (StrUtil.isBlank(parsed.getRunId())) {
            throw new InvalidStreamRequestException("runId 不能为空");
        }

        List<AguiMessage> messages = parsed.getMessages();
        if (messages.isEmpty()) {
            throw new InvalidStreamRequestException("messages 不能为空");
        }
        AguiMessage last = messages.getLast();
        if (Objects.isNull(last) || !last.isUserMessage()) {
            throw new InvalidStreamRequestException("messages 的最后一条必须是 user 消息");
        }
        // 内容块形式的 content（即使全是文本块）getTextContent 取不到文本，下面的空白检查本来就会拒绝，
        // 这里提前拒绝只是为了给出准确的原因，而不是「没有文本」
        if (last.getContent() instanceof MessageContent.Blocks) {
            throw new InvalidStreamRequestException("content 目前只支持字符串，不支持内容块（包括图片、音频、视频、文档）");
        }
        if (StrUtil.isBlank(last.getTextContent())) {
            throw new InvalidStreamRequestException("最后一条 user 消息必须有非空白的文本内容");
        }
        // 重建而不是直接用 parsed：适配器会把 messages 全部转成 Agent 的输入，历史已在状态存储里，
        // 不能再喂一遍；tools、state 等客户端字段也不能带进去。RunAgentInput 不可变，只能用 builder 重新构造。
        // 消息本身也重建，只取文本、id 由服务端生成：原样转交时，客户端带的 toolCalls/toolCallId
        // 会被适配器转成 ToolUseBlock 存进历史，客户端给的 id 也会成为历史里的 Msg id
        AguiMessage userMessage = AguiMessage.userMessage(UUID.randomUUID().toString(), last.getTextContent());
        RunAgentInput input = RunAgentInput.builder()
                .threadId(parsed.getThreadId())
                .runId(parsed.getRunId())
                .messages(List.of(userMessage))
                .build();
        return new StreamRequest(input);
    }

    /**
     * 用适配器运行 Agent，并把 AG-UI 事件编成 SSE 的 data 文本（一个事件一行 JSON）。
     * 不负责关闭 Agent，也不兜底异常，由调用方处理。
     */
    public Flux<String> stream(Agent agent, StreamRequest request, RuntimeContext context) {
        return new AguiAgentAdapter(agent, config)
                .run(request.input, context)
                .map(this::encode);
    }

    /**
     * 把适配器之外的异常（创建 Agent 失败、事件编码失败）编成错误事件，消息原样带出，未脱敏。
     * Agent 运行中的异常由适配器自己转成 {@code RUN_ERROR}，到不了这里。
     *
     * <p>走到这里时请求已经校验通过，所以错误一律是服务端的问题，{@code code} 固定为 {@code INTERNAL_ERROR}，
     * 不照搬适配器按异常类型的映射（那套映射会把配置错误抛出的 {@code IllegalStateException} 标成输入非法）。
     *
     * <p>尚未发出任何事件时先补一个 {@code RUN_STARTED}，保证流总以它开头；它不回显 {@code input}，这一点与适配器不同。
     * 不读 {@code emitRunFinishedAfterError}，出错后不补 {@code RUN_FINISHED}（Yoke 的配置也是关闭的）。
     * 已经开始的文本消息不会补 {@code TEXT_MESSAGE_END}：只有编码中途失败才会出现，属于极少数情况。
     *
     * @param started 是否已经发出过事件
     */
    public Flux<String> encodeError(StreamRequest request, Throwable error, boolean started) {
        String message = Optional.ofNullable(error.getMessage())
                .filter(StrUtil::isNotBlank)
                .orElseGet(() -> error.getClass().getSimpleName());
        String runError = encode(new AguiEvent.RunError(request.threadId(), request.runId(), message, INTERNAL_ERROR_CODE));
        if (started) {
            return Flux.just(runError);
        }
        return Flux.just(encode(new AguiEvent.RunStarted(request.threadId(), request.runId())), runError);
    }

    /** encodeToJson 在 JSON 前带一个空格（给 {@code data:} 凑成 {@code data: {...}}），这里去掉，SSE 规范允许冒号后无空格。 */
    private String encode(AguiEvent event) {
        return encoder.encodeToJson(event).trim();
    }

    /**
     * 解析成 {@link RunAgentInput}。它的构造器要求 {@code threadId}、{@code runId} 非空，缺了会抛
     * {@link NullPointerException}（经 Jackson 包装成别的异常），这里统一按 400 处理。
     */
    private RunAgentInput readInput(String body) {
        RunAgentInput parsed;
        try {
            parsed = JsonUtils.getJsonCodec().fromJson(body, RunAgentInput.class);
        } catch (RuntimeException e) {
            throw new InvalidStreamRequestException("请求体不是合法的 RunAgentInput（threadId、runId、messages 必填）", e);
        }
        if (Objects.isNull(parsed)) {
            throw new InvalidStreamRequestException("请求体不是合法的 JSON 对象");
        }
        return parsed;
    }

    /**
     * 校验通过的流式请求，只带「最后一条 user 消息」。
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class StreamRequest {

        private final RunAgentInput input;

        public String threadId() {
            return input.getThreadId();
        }

        public String runId() {
            return input.getRunId();
        }
    }
}
