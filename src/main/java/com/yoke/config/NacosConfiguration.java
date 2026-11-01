package com.yoke.config;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.yoke.nacos.NacosA2aRegistrar;
import com.yoke.nacos.NacosPromptMiddleware;
import com.yoke.nacos.NacosSupport;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Nacos 接线，开关全部默认关闭且互不绑死：
 * <ul>
 *   <li>{@code yoke.nacos.a2a.enabled}：服务端开启时注册 Agent Card，配了远端 agent-name 时从这里发现；</li>
 *   <li>{@code yoke.nacos.prompt.enabled}：Prompt 热更新；</li>
 *   <li>Skill 托管（nacos-skill）本轮没有接入。</li>
 * </ul>
 * 任何一个开关打开就创建一个共享的 Nacos 客户端（一条 gRPC 通道），并在 Bean 创建时探活，失败则启动失败。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${yoke.nacos.a2a.enabled:false} or ${yoke.nacos.prompt.enabled:false}")
public class NacosConfiguration {

    @Bean(destroyMethod = "shutdown")
    public AiService nacosAiService(YokeProperties yoke) {
        return NacosSupport.connect(yoke.nacos(), yoke.workspace());
    }

    @Bean
    @ConditionalOnProperty(prefix = "yoke.nacos.prompt", name = "enabled", havingValue = "true")
    public NacosPromptMiddleware nacosPromptMiddleware(AiService nacosAiService, YokeProperties yoke) {
        return new NacosPromptMiddleware(nacosAiService, yoke.nacos().prompt().key(), yoke.nacos().prompt().timeout());
    }

    @Bean(destroyMethod = "deregister")
    @ConditionalOnProperty(prefix = "yoke.nacos.a2a", name = "enabled", havingValue = "true")
    public Registration nacosA2aRegistration(AiService nacosAiService, YokeProperties yoke,
            ObjectProvider<AgentScopeA2aServer> server) {
        return new Registration(new NacosA2aRegistrar(nacosAiService, yoke.nacos().a2a().registerAsLatest()), server);
    }

    /** 应用就绪后注册；A2A 服务端没开就只做发现，不注册。 */
    public static class Registration {

        private final NacosA2aRegistrar registrar;
        private final ObjectProvider<AgentScopeA2aServer> server;

        Registration(NacosA2aRegistrar registrar, ObjectProvider<AgentScopeA2aServer> server) {
            this.registrar = registrar;
            this.server = server;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void onReady() {
            AgentScopeA2aServer a2aServer = server.getIfAvailable();
            if (a2aServer == null) {
                log.info("A2A 服务端未开启（yoke.a2a.server.enabled=false），Nacos 只用于发现远端，不注册本服务");
                return;
            }
            AgentCard card = A2aServerConfiguration.publishedCard(a2aServer.getAgentCard());
            registrar.register(card);
        }

        public void deregister() {
            registrar.deregister();
        }
    }
}
