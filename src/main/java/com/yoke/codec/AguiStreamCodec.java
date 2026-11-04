package com.yoke.codec;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
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

    /** threadId 的取值规则，与 {@code @SafeId} 一致：字母数字 {@code _.-}，1~64 位，不能全是点。 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private static final Pattern ALL_DOTS = Pattern.compile("\\.+");

    /** RUN_ERROR 的错误码。与适配器自己对未知异常使用的取值一致，库里没有公开这个常量。 */
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
        if (!isSafeId(parsed.getThreadId())) {
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
        if (!last.isUserMessage()) {
            throw new InvalidStreamRequestException("messages 的最后一条必须是 user 消息");
        }
        if (StrUtil.isBlank(last.getTextContent())) {
            throw new InvalidStreamRequestException("最后一条 user 消息必须有非空白的文本内容");
        }
        // 重建而不是直接用 parsed：适配器会把 messages 全部转成 Agent 的输入，历史已在状态存储里，
        // 不能再喂一遍；tools、state 等客户端字段也不能带进去。RunAgentInput 不可变，只能用 builder 重新构造
        RunAgentInput input = RunAgentInput.builder()
                .threadId(parsed.getThreadId())
                .runId(parsed.getRunId())
                .messages(List.of(last))
                .build();
        return new StreamRequest(input);
    }

    /**
     * 用适配器运行 Agent，并把 AG-UI 事件编成 SSE 的 data 文本（一个事件一行 JSON）。
     * 不负责关闭 Agent，也不兜底异常，由调用方处理。
     */
    public Flux<String> stream(ReActAgent agent, StreamRequest request, RuntimeContext context) {
        return new AguiAgentAdapter(agent, config)
                .run(request.input, context)
                .map(this::encode);
    }

    /** 把流外的异常编成一个 {@code RUN_ERROR} 事件，消息原样带出，未脱敏。 */
    public String encodeError(StreamRequest request, Throwable error) {
        String message = Optional.ofNullable(error.getMessage())
                .filter(StrUtil::isNotBlank)
                .orElseGet(() -> error.getClass().getSimpleName());
        return encode(new AguiEvent.RunError(request.threadId(), request.runId(), message, INTERNAL_ERROR_CODE));
    }

    /** encodeToJson 在 JSON 前带一个空格（给 {@code data:} 凑成 {@code data: {...}}），这里去掉，SSE 规范允许冒号后无空格。 */
    private String encode(AguiEvent event) {
        return encoder.encodeToJson(event).trim();
    }

    private static boolean isSafeId(String id) {
        return Objects.nonNull(id) && SAFE_ID.matcher(id).matches() && !ALL_DOTS.matcher(id).matches();
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
