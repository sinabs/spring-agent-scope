package com.yoke.api;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * A2A（Agent2Agent）服务端接口：让其他 Agent 把 Yoke 当成一个远端 Agent 来调用。<b>默认关闭</b>，
 * 只有 {@code yoke.a2a.server.enabled=true} 时这两个路径才存在，否则是 404。
 *
 * <p><b>安全：开启就是暴露一个无认证的入口。</b>它走同一个 HarnessAgentFactory，能力等价于 {@code /api/chat}
 * （同一批工具、同一个会话存储），调用方还能在请求里自报 {@code userId}。Yoke 目前没有用户体系，
 * 所以生产环境必须把它放在网关后面，或者至少配置 {@code yoke.a2a.server.token}（建议从环境变量注入）：
 * 配置后 JSON-RPC 端点要求 {@code Authorization: Bearer <token>}，缺失或错误返回 401，Agent Card 里同步声明 Bearer 认证。
 * 不配置 token 时不校验，启动日志会打 WARN。Agent Card 本身是公开的（调用方要先读它才知道怎么认证），
 * 里面只有名字、描述、版本、地址和配置的技能说明。
 *
 * <p>传输安全：{@code public-url} 缺省是 {@code http://localhost:端口}，Bearer Token 走的是明文 HTTP。生产必须配置 https 的对外地址，
 * 或者由网关终结 TLS，否则 token 会在网络上明文传输。
 *
 * <p>协议：官方 2.0.4 实现的是 A2A 0.3 版本（SDK 0.3.3.Final，Agent Card 的 protocolVersion 为 0.3.0）。以 2.0.4 为准，
 * 更新版本的 AgentScope 已改为 A2A 1.0、不再支持 0.3，升级前请以对应版本的手册为准。
 * 传输只有 JSON-RPC。<b>只支持同步非流式的 {@code message/send}</b>：Agent Card 里 {@code capabilities.streaming=false}；
 * {@code message/stream}、{@code tasks/resubscribe} 返回「不支持的操作」，{@code tasks/get}、{@code tasks/cancel}、
 * 推送通知配置等其余方法返回「方法不存在」；{@code blocking} 一律按 true 处理，调用在 Agent 跑完之后才返回。
 *
 * <p>会话映射：sessionId 取 {@code message.metadata.sessionId}，没有则取 {@code message.contextId}，再没有就新建；
 * userId 取 {@code message.metadata.userId}，缺失为 {@code default}。二者必须符合与 HTTP 接口相同的 {@code @SafeId} 规则
 * （字母、数字、下划线、点、短横线，1 到 64 位，不能全是点），不合法返回 JSON-RPC 的 -32602（InvalidParams）而不是放行。
 * 请求体超过 {@code yoke.a2a.server.max-body-bytes}（默认 256 KB）返回 413（响应体 -32600），认证先于读体，见 {@link #message}；{@code /api/chat} 没有这类自定义上限，只受容器默认值约束。
 * 响应里的 {@code contextId} 就是会话 ID，下一次带上它即可延续对话。不支持用 {@code taskId} 接续任务。
 *
 * <p>入站消息一律降级成一条<b>用户消息</b>交给 Agent：part 元数据里自报的角色、工具调用块、工具结果块都不会被当真，
 * 非文本的 part 也不会作为多媒体传给模型。注意框架会把 data 类型的 part 转成文本（实测），所以它的内容仍会作为用户输入出现。
 *
 * <p>错误：执行失败返回 JSON-RPC 的 -32603（InternalError），消息只有「智能体执行失败」或「智能体执行超时」，
 * 不含异常信息和堆栈（详情只在服务端日志里）。总超时见 {@code yoke.a2a.server.timeout}，默认 120 秒。
 *
 * <p>实现说明：这个入口是阻塞的（框架的 JSON-RPC 处理链是同步的），靠虚拟线程撑住并发，和响应式的 {@code /api/chat} 不同；
 * 同一个会话的并发请求同样不会被串行化。
 */
public interface A2aApi {

    /**
     * Agent Card（well-known 地址），公开，不需要认证。
     *
     * @return Agent Card 的 JSON
     */
    @GetMapping(path = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> agentCard();

    /**
     * A2A 的 JSON-RPC 端点，只处理 {@code message/send}，见类说明。
     *
     * <p>处理顺序：<b>先认证，再看请求体大小，最后才解析</b>。请求体不由 Spring 预先读入，而是由实现自己读取：
     * 认证失败（401）不读体；声明的 Content-Length 超过 {@code yoke.a2a.server.max-body-bytes} 直接回 413，也不读体；
     * 没声明长度（chunked）的请求限长读取，超过上限就停止读取并回 413。413 的响应体是 JSON-RPC 的 -32600。
     * 早先的版本用 {@code @RequestBody String}，Spring 会在认证之前把整个请求体读进内存，未认证的调用者就能让服务器缓冲任意大的数据，
     * 现在已修正。
     *
     * @param request 原始请求，用来读 Authorization 和请求体
     * @return JSON-RPC 响应；认证失败是 401，请求体过大是 413
     */
    @PostMapping(path = "/a2a", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> message(HttpServletRequest request);
}
