package com.yoke.api;

import com.yoke.model.ChatResponse;
import com.yoke.model.RemoteChatRequest;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 把一句话转发给配置好的远端 A2A Agent。<b>默认不存在</b>：只有配置了 {@code yoke.a2a.remote.url}
 * 或 {@code yoke.a2a.remote.agent-name} 时才注册，否则这个路径是 404。
 *
 * <p>远端地址只来自配置，请求体里没有任何能指定地址的字段，所以不能借它访问任意地址（防 SSRF）。直连 URL 时，远端 Agent Card 里<b>所有</b>声明服务地址的位置
 * （{@code url} 和 {@code additionalInterfaces} 的每一项）都必须与配置同源，首选传输必须是 JSONRPC，否则拒绝调用；
 * HTTP 客户端不跟随重定向，请求有超时。
 * 它是个无认证的转发入口，和 {@code /api/chat} 一样：谁能访问 Yoke 就能让 Yoke 以自己的身份（含配置的远端 token）
 * 去调用远端，生产必须放在网关后面。
 *
 * <p>它没有放进模型的工具清单：让模型自己决定调用远端，会把远端的回复当作模型可见的输入，
 * 远端内容里的提示注入就能影响后续推理，也会让 {@code ToolPermissionTests} 守着的工具清单变长。
 * 需要时由调用方显式走这个接口，回复原样返回、不再交给模型处理。
 *
 * <p>失败的表现：远端不可达、返回错误、Agent Card 与配置地址不同源时返回 502；超过 {@code yoke.a2a.remote.timeout}
 * （默认 30 秒）返回 504；响应体不含地址、凭证和远端的原文。
 */
@RequestMapping("/api/chat/remote")
public interface RemoteChatApi {

    /**
     * 向远端 Agent 发送一条消息并返回它的文本回复。
     *
     * <p>{@code sessionId} 会作为 A2A 的 {@code contextId} 和 {@code metadata.sessionId} 传给远端，
     * 远端按它延续对话；不传则新建，并在响应里返回。当前没有用户体系，调用远端时不带 userId，远端按它自己的默认用户处理。
     *
     * @param request 远端请求，包含可选的会话 ID 和必填的消息
     * @return 远端的回复和本次会话 ID
     */
    @PostMapping
    Mono<ChatResponse> ask(@Valid @RequestBody RemoteChatRequest request);
}
