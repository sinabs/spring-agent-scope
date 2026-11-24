package com.yoke.a2a;

import java.time.Duration;
import java.util.Map;

import io.a2a.A2A;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;

/** well-known 取卡片，用不跟随重定向、带超时的 HTTP 客户端（官方的 WellKnownAgentCardResolver 用的是会跟随重定向的默认客户端）。 */
public class NoRedirectCardResolver implements AgentCardResolver {

    private final NoRedirectHttpClient http;
    private final String baseUrl;

    public NoRedirectCardResolver(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl;
        this.http = new NoRedirectHttpClient(timeout);
    }

    @Override
    public AgentCard getAgentCard(String agentName) {
        return A2A.getAgentCard(http, baseUrl, "/.well-known/agent-card.json", Map.of());
    }

    /** 关闭取卡片所用的底层 HTTP 客户端。 */
    public void close() {
        http.close();
    }

    /** 底层 HTTP 客户端是否已关闭，供生命周期测试断言。 */
    public boolean isTerminated() {
        return http.isTerminated();
    }
}
