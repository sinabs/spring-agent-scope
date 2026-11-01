package com.yoke.config;

import com.alibaba.nacos.api.ai.AiService;
import com.yoke.a2a.NoRedirectCardResolver;
import com.yoke.a2a.RemoteAgentClient;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 远端 A2A Agent 的客户端装配，配置了 {@code yoke.a2a.remote.url} 或 {@code yoke.a2a.remote.agent-name} 才生效。
 *
 * <p>Agent Card 的来源有两个，同一时刻只用一个，选择规则集中在 {@link #cardSource}：
 * <ul>
 *   <li>配了 {@code agent-name} 且 {@code yoke.nacos.a2a.enabled=true}：从 Nacos 按名字发现（优先）；
 *       此时如果同时配了 {@code url}，url 被忽略并打一条 WARN；</li>
 *   <li>只配了 {@code url}：well-known 直连；</li>
 *   <li>配了 {@code agent-name} 但 Nacos 没开：启动失败，而不是悄悄退回到别的来源。</li>
 * </ul>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnRemoteAgent
public class A2aClientConfiguration {

    /** 取卡片的来源决定调用的是哪个地址，只来自配置。 */
    public enum CardSource { NACOS, WELL_KNOWN }

    @Bean
    public RemoteAgentClient remoteAgentClient(YokeProperties yoke, ObjectProvider<AiService> nacos) {
        YokeProperties.A2a.Remote remote = yoke.a2a().remote();
        CardSource source = cardSource(yoke);
        if (source == CardSource.NACOS) {
            if (StringUtils.hasText(remote.url())) {
                log.warn("同时配置了 yoke.a2a.remote.url 和 agent-name，且 Nacos 已开启：使用 Nacos 发现，忽略 url");
            }
            AiService aiService = nacos.getObject();
            AgentCardResolver resolver = new NacosAgentCardResolver(aiService);
            log.info("远端 A2A Agent 来源：Nacos（agent-name={}）", remote.agentName());
            // Nacos 发现时地址由注册中心提供，不做同源限制：信任边界是 Nacos
            return new RemoteAgentClient(resolver, remote.agentName(), remote.token(), remote.timeout(), null);
        }
        String url = remote.url().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalStateException("yoke.a2a.remote.url 必须以 http:// 或 https:// 开头");
        }
        AgentCardResolver resolver = new NoRedirectCardResolver(url, remote.timeout());
        log.info("远端 A2A Agent 来源：well-known 直连 {}", RemoteAgentClient.originOf(url));
        return new RemoteAgentClient(resolver, "remote", remote.token(), remote.timeout(),
                RemoteAgentClient.originOf(url));
    }

    /** 选择规则，单独成方法便于直接测试。配置自相矛盾时抛出带说明的异常。 */
    public static CardSource cardSource(YokeProperties yoke) {
        YokeProperties.A2a.Remote remote = yoke.a2a().remote();
        boolean byName = StringUtils.hasText(remote.agentName());
        if (byName && yoke.nacos().a2a().enabled()) {
            return CardSource.NACOS;
        }
        if (byName) {
            throw new IllegalStateException("配置了 yoke.a2a.remote.agent-name（按名字从 Nacos 发现远端），"
                    + "但 yoke.nacos.a2a.enabled 没有开启；要么开启 Nacos，要么改用 yoke.a2a.remote.url 直连");
        }
        return CardSource.WELL_KNOWN;
    }
}
