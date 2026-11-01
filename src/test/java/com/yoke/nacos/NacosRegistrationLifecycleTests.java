package com.yoke.nacos;

import java.time.Duration;
import java.util.List;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint;
import com.yoke.config.NacosConfiguration;
import com.yoke.config.YokeProperties;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册与注销的生命周期接线（不需要 Docker）：用 mock 的 AiService 观察容器的行为。
 * 关键是「关闭上下文时 Spring 会调用注销」，也就是 {@code @Bean(destroyMethod = "deregister")} 这条接线；
 * 真实 Nacos 的测试里，客户端断开连接也会让服务端摘掉临时实例，分辨不出注销有没有被调用。
 */
class NacosRegistrationLifecycleTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(YokeProperties.class)
    static class Props {
    }

    private static AgentCard card() {
        return new AgentCard.Builder().name("lifecycle-agent").description("d").url("http://127.0.0.1:45679/a2a")
                .version("1.0.0").protocolVersion("0.3.0").preferredTransport("JSONRPC")
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of()).build();
    }

    private ApplicationContextRunner runner(AiService ai, AgentScopeA2aServer server) {
        return new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(true)
                .withPropertyValues("yoke.workspace=/tmp/yoke-lifecycle", "yoke.redis.uri=redis://127.0.0.1:6379",
                        "yoke.nacos.a2a.enabled=true")
                .withBean("nacosAiService", AiService.class, () -> ai)
                .withBean(AgentScopeA2aServer.class, () -> server)
                .withUserConfiguration(Props.class, NacosConfiguration.class);
    }

    private static void publishReady(org.springframework.context.ConfigurableApplicationContext context) {
        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
    }

    @Test
    void 就绪后注册_关闭上下文时注销() throws Exception {
        AiService ai = mock(AiService.class);
        AgentScopeA2aServer server = mock(AgentScopeA2aServer.class);
        when(server.getAgentCard()).thenReturn(card());

        runner(ai, server).run(context -> {
            verify(ai, never()).deregisterAgentEndpoint(any(String.class), any(AgentEndpoint.class));
            publishReady(context);
            verify(ai).registerAgentEndpoint(eq("lifecycle-agent"), any(AgentEndpoint.class));
        });

        // run 结束时上下文已关闭，Spring 应当调用了注销
        verify(ai).deregisterAgentEndpoint(eq("lifecycle-agent"), any(AgentEndpoint.class));
    }

    @Test
    void 没有注册过就不会注销() throws Exception {
        AiService ai = mock(AiService.class);

        runner(ai, mock(AgentScopeA2aServer.class)).run(context -> assertThat(context).hasNotFailed());

        verify(ai, never()).deregisterAgentEndpoint(any(String.class), any(AgentEndpoint.class));
    }
}
