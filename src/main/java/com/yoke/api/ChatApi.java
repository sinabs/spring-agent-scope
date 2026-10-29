package com.yoke.api;

import com.yoke.model.ChatRequest;
import com.yoke.model.ChatResponse;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

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
}
