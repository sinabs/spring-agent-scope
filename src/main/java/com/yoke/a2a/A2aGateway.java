package com.yoke.a2a;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoke.a2a.runner.YokeAgentRunner;
import com.yoke.model.SafeId;
import io.a2a.spec.AgentCard;
import io.a2a.spec.InternalError;
import io.a2a.spec.InvalidParamsError;
import io.a2a.spec.InvalidRequestError;
import io.a2a.spec.JSONParseError;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.Message;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.SendStreamingMessageRequest;
import io.a2a.spec.TransportProtocol;
import io.a2a.spec.UnsupportedOperationError;
import io.a2a.util.Utils;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import lombok.extern.slf4j.Slf4j;


/**
 * A2A 服务端的入口网关：HTTP 请求进来后，先在这里认证、校验、收紧，再交给框架的 JSON-RPC 处理链。
 *
 * <p>框架的 JSON-RPC 处理链没有任何认证（源码里写着 TODO），也不校验标识格式，所以这些都在这一层补：
 * <ol>
 *   <li><b>认证</b>：配置了 token 时要求 {@code Authorization: Bearer <token>}，常量时间比较；没配则不校验；</li>
 *   <li><b>请求体大小上限</b>（{@code yoke.a2a.server.max-body-bytes}，默认 256 KB）：认证先于读体（{@link #preflight}），
 *       再看声明的 Content-Length，超限不读体直接回 413；没声明长度的（chunked）用限长读取，超过上限就停止读取。
 *       响应体是 JSON-RPC 的 -32600。所以未认证的调用者没法让服务器缓冲任意大的请求体；</li>
 *   <li><b>只开放同步的 {@code message/send}</b>：流式（{@code message/stream}、{@code tasks/resubscribe}）返回「不支持」，
 *       其余方法（取消、查询任务、推送配置）返回「方法不存在」，攻击面最小；{@code blocking} 一律强制为 true；</li>
 *   <li><b>会话映射</b>：sessionId 取 {@code metadata.sessionId}，没有则取 {@code message.contextId}，再没有就新建；
 *       userId 取 {@code metadata.userId}，缺失用 default。二者都过 {@link SafeId} 规则，非法值返回 -32602 而不是放行；
 *       最终的 sessionId 写回 {@code contextId}，所以响应里的 contextId 就是会话 ID，调用方带着它即可延续对话；</li>
 *   <li><b>不接续任务</b>：带 {@code taskId} 的请求被拒绝，延续对话请用 contextId；</li>
 *   <li><b>错误改写</b>：执行失败时把框架生成的「看起来像正常回复」的错误消息改写成 JSON-RPC 的 -32603，信息不含内部细节。</li>
 * </ol>
 *
 * <p>日志里不记请求体（里面是用户内容）和 Authorization 头。
 */
@Slf4j
public class A2aGateway {

    /** 框架的 JSON-RPC 方法名：同步发送消息，是唯一放行的方法。 */
    static final String METHOD_SEND = SendMessageRequest.METHOD;

    private final AgentScopeA2aServer server;
    private final YokeAgentRunner runner;
    private final AgentCard publishedCard;
    private final byte[] tokenDigest;
    private final int maxBodyBytes;

    /**
     * @param server        框架的 A2A 服务端
     * @param runner        取失败台账用
     * @param publishedCard 对外公布的 Agent Card（已按 Yoke 的实际能力修正）
     * @param token         Bearer Token；空白表示不校验
     * @param maxBodyBytes  请求体上限（字节）；认证通过之后、解析之前检查
     */
    public A2aGateway(AgentScopeA2aServer server, YokeAgentRunner runner, AgentCard publishedCard, String token,
            int maxBodyBytes) {
        this.server = server;
        this.runner = runner;
        this.publishedCard = publishedCard;
        this.tokenDigest = Optional.ofNullable(token).filter(StrUtil::isNotBlank).map(A2aGateway::sha256).orElse(null);
        this.maxBodyBytes = maxBodyBytes;
    }

    /** 一次网关响应：HTTP 状态码、JSON 响应体、需要时的认证质询头。 */
    public record Reply(int status, String body, String wwwAuthenticate) {
    }

    /** Agent Card 的 JSON；公开，不需要 token（调用方要先读它才知道该怎么认证）。 */
    public String agentCardJson() {
        return toJson(publishedCard);
    }

    /**
     * 读请求体<b>之前</b>的检查：先认证，再看声明的 Content-Length。返回 null 表示可以继续读体，否则直接用返回值作答，
     * 请求体一个字节都不读。这样未认证的调用者没法让服务器缓冲任意大的请求体。
     */
    public Reply preflight(Map<String, String> headers, long declaredLength) {
        if (tokenRejected(headers)) {
            return unauthorized();
        }
        if (declaredLength > maxBodyBytes) {
            return tooLarge();
        }
        return null;
    }

    /**
     * 限长读取请求体：最多读 {@code maxBodyBytes + 1} 个字节，超过就停止读取并返回 null（调用方应回 {@link #tooLarge()}）。
     * 对没有声明长度（chunked）的请求同样有效。
     */
    public String readBody(InputStream in) throws IOException {
        byte[] data = in.readNBytes(maxBodyBytes + 1);
        if (data.length > maxBodyBytes) {
            return null;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /** 413，响应体是 JSON-RPC 的 -32600；不含内部信息。 */
    public Reply tooLarge() {
        Reply error = rpcError(null, new InvalidRequestError("请求体过大，上限 " + maxBodyBytes + " 字节"));
        return new Reply(413, error.body(), null);
    }

    /** 读请求体失败。 */
    public Reply unreadable() {
        Reply error = rpcError(null, new InvalidRequestError("无法读取请求体"));
        return new Reply(400, error.body(), null);
    }

    private static Reply unauthorized() {
        return new Reply(401, "{\"error\":\"unauthorized\"}", "Bearer");
    }

    public Reply handle(String body, Map<String, String> headers) {
        if (tokenRejected(headers)) {
            return unauthorized();
        }
        // 大小只在字节层检查一次（preflight 的声明长度 + readBody 的限长读取）；这里不再对已解码的字符串重算字节数，
        // 含非法 UTF-8 的输入解码后会被替换字符撑长，重算会误伤
        if (Objects.isNull(body)) {
            return unreadable();
        }
        JsonNode root;
        try {
            root = Utils.OBJECT_MAPPER.readTree(body);
        } catch (JsonProcessingException | RuntimeException e) {
            return rpcError(null, new JSONParseError("请求体不是合法的 JSON"));
        }
        if (Objects.isNull(root) || !root.isObject()) {
            return rpcError(null, new InvalidRequestError());
        }
        Object id = idOf(root);
        String method = root.path("method").asText("");
        if (SendStreamingMessageRequest.METHOD.equals(method) || "tasks/resubscribe".equals(method)) {
            return rpcError(id, new UnsupportedOperationError());
        }
        if (!METHOD_SEND.equals(method)) {
            return rpcError(id, new MethodNotFoundError());
        }
        ObjectNode forwarded;
        try {
            forwarded = normalize((ObjectNode) root);
        } catch (IllegalArgumentException e) {
            return rpcError(id, new InvalidParamsError(e.getMessage()));
        }
        return dispatch(id, forwarded.toString(), headers);
    }

    private Reply dispatch(Object id, String body, Map<String, String> headers) {
        Map<String, String> forwardedHeaders = new HashMap<>(headers);
        // 凭证不往下传：框架会把请求头放进调用上下文
        forwardedHeaders.keySet().removeIf(name -> name.equalsIgnoreCase("authorization"));
        Object result = server.getTransportWrapper(TransportProtocol.JSONRPC.asString(), JsonRpcTransportWrapper.class)
                .handleRequest(body, forwardedHeaders, Map.of());
        if (!(result instanceof JSONRPCResponse<?> response)) {
            log.error("A2A 处理链返回了意料之外的类型：{}", Optional.ofNullable(result).map(value -> value.getClass().getName()).orElse(null));
            return rpcError(id, new InternalError("服务端内部错误"));
        }
        if (response instanceof SendMessageResponse sent && sent.getResult() instanceof Message message) {
            var failure = runner.takeFailure(message.getTaskId());
            if (failure.isPresent()) {
                return rpcError(id, new InternalError(failure.get()));
            }
        }
        return new Reply(200, toJson(response), null);
    }

    /** 校验并改写 message/send 的参数，返回要转交给框架的请求。非法输入抛 IllegalArgumentException。 */
    private static ObjectNode normalize(ObjectNode root) {
        JsonNode params = root.path("params");
        if (!params.isObject() || !params.path("message").isObject()) {
            throw new IllegalArgumentException("缺少 params.message");
        }
        ObjectNode message = (ObjectNode) params.get("message");
        if (hasValue(message.get("taskId"))) {
            throw new IllegalArgumentException("不支持接续任务（taskId），延续对话请使用 contextId");
        }
        JsonNode metadataNode = message.get("metadata");
        if (Objects.nonNull(metadataNode) && !metadataNode.isNull() && !metadataNode.isObject()) {
            throw new IllegalArgumentException("message.metadata 必须是对象");
        }
        ObjectNode metadata = Objects.nonNull(metadataNode) && metadataNode.isObject()
                ? (ObjectNode) metadataNode : message.putObject("metadata");

        String userId = textOrNull(metadata, "userId");
        String sessionId = textOrNull(metadata, "sessionId");
        String contextId = textOrNull(message, "contextId");
        if (StrUtil.isBlank(sessionId)) {
            sessionId = contextId;
        }
        if (StrUtil.isNotBlank(userId) && !SafeId.Validator.isSafe(userId)) {
            throw new IllegalArgumentException("userId 格式不合法：只能包含字母、数字、下划线、点和短横线，长度 1 到 64，且不能全是点");
        }
        if (StrUtil.isNotBlank(sessionId) && !SafeId.Validator.isSafe(sessionId)) {
            throw new IllegalArgumentException("sessionId（或 contextId）格式不合法：只能包含字母、数字、下划线、点和短横线，长度 1 到 64，且不能全是点");
        }
        if (StrUtil.isBlank(sessionId)) {
            sessionId = UUID.randomUUID().toString();
        }
        metadata.put("sessionId", sessionId);
        message.put("contextId", sessionId);
        if (StrUtil.isNotBlank(userId)) {
            metadata.put("userId", userId);
        } else {
            metadata.remove("userId");
        }
        // 同步语义：调用在 Agent 跑完之后才返回，不留异步任务让调用方轮询
        ObjectNode configuration = params.path("configuration").isObject()
                ? (ObjectNode) params.get("configuration") : ((ObjectNode) params).putObject("configuration");
        configuration.put("blocking", true);
        return root;
    }

    private static boolean hasValue(JsonNode node) {
        return Objects.nonNull(node) && !node.isNull() && StrUtil.isNotBlank(node.asText());
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (Objects.isNull(node) || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " 必须是字符串");
        }
        return node.asText();
    }

    private static Object idOf(JsonNode root) {
        JsonNode id = root.get("id");
        if (Objects.isNull(id) || id.isNull()) {
            return null;
        }
        return id.isNumber() ? id.numberValue() : id.asText();
    }

    /** 配置了 token、而请求没带对的 token 时返回 true；没配 token 不校验。 */
    private boolean tokenRejected(Map<String, String> headers) {
        if (Objects.isNull(tokenDigest)) {
            return false;
        }
        String header = headers.entrySet().stream()
                .filter(entry -> "authorization".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        if (Objects.isNull(header) || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return true;
        }
        // 先各自取摘要再比较：长度固定，常量时间，也不泄露 token 的长度
        return !MessageDigest.isEqual(tokenDigest, sha256(header.substring(7).trim()));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Reply rpcError(Object id, JSONRPCError error) {
        return new Reply(200, toJson(new JSONRPCErrorResponse(id, error)), null);
    }

    private static String toJson(Object value) {
        try {
            return Utils.OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 A2A 响应失败", e);
        }
    }
}
