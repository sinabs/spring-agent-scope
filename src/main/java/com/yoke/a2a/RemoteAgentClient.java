package com.yoke.a2a;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import cn.hutool.core.util.StrUtil;
import com.yoke.codec.MessageCodec;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.client.transport.spi.interceptors.ClientCallContext;
import io.a2a.client.transport.spi.interceptors.ClientCallInterceptor;
import io.a2a.client.transport.spi.interceptors.PayloadAndHeaders;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.SendMessageRequest;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.A2aAgentConfig;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


/**
 * 把一个远端 A2A Agent 包成本地调用：用官方的 {@link A2aAgent} 发 {@code message/send}，拿回文本。
 *
 * <p>远端地址只来自配置：Agent Card 由构造时给定的 {@link AgentCardResolver} 取（well-known 直连或 Nacos 发现），
 * 请求体里的任何字段都不会影响调用的目标，所以调用方没法借它去访问任意地址。
 *
 * <p>每次调用新建一个 {@link A2aAgent}：它自带内存记忆，会把历史消息全部重发，也不允许并发调用；
 * 会话的连续性交给远端按 sessionId 保存的历史，而不是本地对象。sessionId 同时写进 {@code contextId} 和
 * {@code metadata.sessionId}（官方 A2aAgent 本身不传会话标识，这里用传输层拦截器补上）。
 *
 * <p>失败一律包成 {@link RemoteAgentException}，不静默吞：取卡片失败、连接失败、对方返回错误、超时都有明确的异常；
 * 消息里不含地址和凭证。
 *
 * <p>HTTP 层用 {@link NoRedirectHttpClient}：不跟随重定向（否则远端一个 307 就能把消息体重发到任意主机）、请求有超时。
 * 卡片里的 {@code securitySchemes} / {@code security} 不会让客户端自动带出凭证：官方 ClientBuilder 完全不读它们
 * （读字节码所得），凭证只有我们自己在拦截器里加的那一个 Authorization。
 *
 * <p>当 {@code pinnedOrigin} 不为空（well-known 直连）时，要求 Agent Card 里<b>所有</b>声明的服务地址与配置的地址同源，且首选传输是 JSONRPC，
 * 否则拒绝调用——否则远端一张改过的卡片就能把请求（连同 token）引到别处。Nacos 发现时不做这个限制，
 * 信任边界是 Nacos 本身。
 */
@Slf4j
public class RemoteAgentClient {

    private final AgentCardResolver cardResolver;
    private final String agentName;
    private final String token;
    private final Duration timeout;
    private final String pinnedOrigin;

    public RemoteAgentClient(AgentCardResolver cardResolver, String agentName, String token, Duration timeout,
            String pinnedOrigin) {
        this.cardResolver = cardResolver;
        this.agentName = agentName;
        this.token = token;
        this.timeout = timeout;
        this.pinnedOrigin = pinnedOrigin;
    }

    /** 向远端发一句话，返回远端回复的文本。 */
    public Mono<String> ask(String sessionId, String message) {
        return Mono.fromCallable(() -> prepare(sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(agent -> agent.call(MessageCodec.toInput(message)))
                .map(MessageCodec::toText)
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof RemoteAgentException), this::wrap);
    }

    private A2aAgent prepare(String sessionId) {
        AgentCard card = cardResolver.getAgentCard(agentName);
        if (card == null) {
            throw new IllegalStateException("没有取到远端的 Agent Card");
        }
        if (pinnedOrigin != null && !cardStaysInOrigin(card)) {
            throw new RemoteAgentException("远端 Agent Card 声明的服务地址与配置的地址不一致，已拒绝调用", false, null);
        }
        JSONRPCTransportConfig transport = new JSONRPCTransportConfig(new NoRedirectHttpClient(timeout));
        transport.setInterceptors(List.of(new SessionAndAuth(sessionId, token)));
        return A2aAgent.builder()
                .name(agentName)
                .agentCard(card)
                .a2aAgentConfig(A2aAgentConfig.builder().withTransport(JSONRPCTransport.class, transport).build())
                .build();
    }

    /**
     * Card 里<b>所有</b>声明服务地址的位置都必须与配置同源，并且首选传输必须是 JSONRPC。
     *
     * <p>只比较 {@code card.url} 会被绕过：官方 ClientBuilder 选传输时把「首选传输 -> url」和
     * 「additionalInterfaces 里每一项」放进同一张表，再挑 JSONRPC（读 2.0.4 的 a2a-java-sdk 0.3.3.Final 字节码所得）。
     * 远端把 url 写成同源、首选传输写成别的，并在 additionalInterfaces 里放一个指向别处的 JSONRPC 地址，
     * 客户端就会改用后者，消息和 token 被引到任意地址。所以这里宁严勿松：首选传输不是 JSONRPC 就拒绝，
     * additionalInterfaces 里任何一项不同源（不管什么传输）也拒绝。代价：把对外地址和内部地址拆开写的远端会被拒，
     * 这时把 {@code yoke.a2a.remote.url} 配成它对外声明的地址。
     */
    private boolean cardStaysInOrigin(AgentCard card) {
        try {
            String preferred = card.preferredTransport();
            if (StrUtil.isNotBlank(preferred) && !"JSONRPC".equalsIgnoreCase(preferred)) {
                return false;
            }
            if (!pinnedOrigin.equals(originOf(card.url()))) {
                return false;
            }
            if (card.additionalInterfaces() != null) {
                for (var each : card.additionalInterfaces()) {
                    if (!pinnedOrigin.equals(originOf(each.url()))) {
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private RemoteAgentException wrap(Throwable error) {
        boolean timedOut = isTimeout(error);
        // 底层原因只进服务端日志，异常消息里不带地址和对方返回的原文
        log.warn("调用远端 A2A Agent 失败：{}", error.toString());
        return new RemoteAgentException(timedOut ? "调用远端 Agent 超时（" + timeout.toSeconds() + " 秒）"
                : "调用远端 Agent 失败（" + error.getClass().getSimpleName() + "）", timedOut, error);
    }

    /** 响应式超时和 HTTP 请求超时谁先到都算超时（二者设成同一个时长，会竞争）。 */
    private static boolean isTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 协议 + 主机 + 端口（端口缺省时按协议补全），用来比较「是不是同一个服务」。 */
    public static String originOf(String url) {
        if (!StrUtil.isNotBlank(url)) {
            return "";
        }
        URI uri = URI.create(url);
        // 主机名里有下划线之类，URI 能解析但 getHost() 是 null：两边都变成 "…://null:80" 会被误判为同源，所以直接拒绝
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("无法从地址中解析出协议和主机");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return (uri.getScheme() + "://" + uri.getHost() + ":" + port).toLowerCase();
    }

    /** 取卡片用的解析器；生产装配必须是不跟随重定向的那个，装配测试靠它断言。 */
    public AgentCardResolver cardResolver() {
        return cardResolver;
    }

    /** 给发出的 message/send 补上会话标识，并带上 Bearer Token。 */
    private static final class SessionAndAuth extends ClientCallInterceptor {

        private final String sessionId;
        private final String token;

        SessionAndAuth(String sessionId, String token) {
            this.sessionId = sessionId;
            this.token = token;
        }

        @Override
        public PayloadAndHeaders intercept(String methodName, Object payload, Map<String, String> headers,
                AgentCard agentCard, ClientCallContext context) {
            Object forwarded = payload;
            if (payload instanceof SendMessageRequest request && StrUtil.isNotBlank(sessionId)) {
                Message original = request.getParams().message();
                Map<String, Object> metadata = new HashMap<>();
                if (original.getMetadata() != null) {
                    metadata.putAll(original.getMetadata());
                }
                metadata.put("sessionId", sessionId);
                Message message = new Message.Builder(original).contextId(sessionId).metadata(metadata).build();
                MessageSendParams params = request.getParams();
                forwarded = new SendMessageRequest(request.getId(),
                        new MessageSendParams(message, params.configuration(), params.metadata()));
            }
            Map<String, String> allHeaders = new HashMap<>(headers == null ? Map.of() : headers);
            if (StrUtil.isNotBlank(token)) {
                allHeaders.put("Authorization", "Bearer " + token);
            }
            return new PayloadAndHeaders(forwarded, allHeaders);
        }
    }
}
