package com.yoke.api;

import com.yoke.model.ChatEvent;
import com.yoke.model.ChatRequest;
import com.yoke.model.ChatResponse;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 与 Agent 对话的 HTTP 接口契约。
 *
 * <p>每次请求都会新建一个临时 Agent，用完即关闭；对话历史保存在共享的状态存储中，
 * 按会话 ID 隔离，所以新建的 Agent 仍能接上同一会话之前的对话。
 * 路径和参数注解都声明在这里，实现类只负责业务逻辑。
 *
 * <p>实现说明：暂时没有用户体系，所有会话共用同一个用户标识；默认的状态存储在内存中，服务重启后历史丢失；
 * 同一个会话的并发请求不会被串行化，由调用方保证同一会话同一时间只发一个请求。
 */
@RequestMapping("/api/chat")
public interface ChatApi {

    /**
     * 向 Agent 发送一条消息并返回回复。
     *
     * <p>同一个 {@code sessionId} 共享对话历史；不传 {@code sessionId} 时由服务端新建会话，
     * 并在响应里返回新的会话 ID，后续请求带上它即可延续对话。
     *
     * <p>{@code message} 为必填：缺失、为 null、空串或纯空白时都由参数校验拦截，返回 400。
     *
     * @param request 对话请求，包含可选的会话 ID 和必填的用户输入
     * @return Agent 的回复，以及本次对话所属的会话 ID
     */
    @PostMapping
    Mono<ChatResponse> chat(@Valid @RequestBody ChatRequest request);

    /**
     * 向 Agent 发送一条消息，以 SSE（{@code text/event-stream}）边推理边推送事件。
     *
     * <p>请求体和会话规则与 {@link #chat} 相同：同一个 {@code sessionId} 共享对话历史，
     * 不传时由服务端新建会话，新的会话 ID 出现在最后的 {@code done} 事件里；{@code message} 缺失、
     * 为 null、空串或纯空白时在建立流之前就被参数校验拦截，返回 400。
     *
     * <p>SSE 的 event 名与事件类型一一对应，data 是 JSON，一次对话可能依次出现：
     * <ul>
     *   <li>{@code textDelta}：模型生成的一段文本增量，按顺序拼接即得到回复；</li>
     *   <li>{@code toolCall}：模型决定调用的工具，名称和入参已经攒齐；</li>
     *   <li>{@code toolResult}：工具的执行结果，与 {@code toolCall} 靠相同的 {@code id} 对应；</li>
     *   <li>{@code hint}：框架在推理过程中追加的提示；</li>
     *   <li>{@code done}：正常结束，带完整回复文本、会话 ID 和累计 token 用量；</li>
     *   <li>{@code error}：推理过程中出错。</li>
     * </ul>
     * 一次对话以 {@code done} 或 {@code error} 结尾，二者必有其一且只会出现一个。
     * 这个承诺依赖 2.0.4 框架在流结束时总会发出结束事件，实现里没有额外的兜底。
     *
     * <p>{@code error} 事件会原样带出异常信息，未做脱敏，调用方不要直接展示给终端用户。
     *
     * <p>错误通过 {@code error} 事件体现，HTTP 状态码仍是 200 并正常结束流，而不是中途断开连接，
     * 所以调用方只需要根据最后一个事件判断成败。
     *
     * <p>每次请求新建一个临时 Agent：流正常结束、出错或被客户端取消（断开连接）时都会关闭它。
     *
     * <p>已知限制：与 {@link #chat} 一样，同一个会话的并发请求不会被串行化，
     * 由调用方保证同一会话同一时间只发一个请求；取消时已经产生的部分对话是否写入历史，取决于框架的保存时机。
     *
     * @param request 对话请求，包含可选的会话 ID 和必填的用户输入
     * @return 事件流，最后一个事件是 {@code done} 或 {@code error}
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<ChatEvent>> stream(@Valid @RequestBody ChatRequest request);
}
