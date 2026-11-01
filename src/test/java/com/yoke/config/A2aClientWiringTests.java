package com.yoke.config;

import com.yoke.a2a.NoRedirectCardResolver;
import com.yoke.a2a.RemoteAgentClient;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** 生产装配本身要被测试盯住：well-known 直连用的必须是不跟随重定向的卡片解析器，而不是官方那个。 */
class A2aClientWiringTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(YokeProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("yoke.workspace=/tmp/yoke-wiring", "yoke.redis.uri=redis://127.0.0.1:6379")
            .withUserConfiguration(Props.class, A2aClientConfiguration.class);

    @Test
    void 直连模式装配出不跟随重定向的卡片解析器() {
        runner.withPropertyValues("yoke.a2a.remote.url=http://127.0.0.1:1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RemoteAgentClient.class).cardResolver()).isInstanceOf(NoRedirectCardResolver.class);
        });
    }

    @Test
    void 没有配置远端时不装配客户端() {
        runner.run(context -> assertThat(context).doesNotHaveBean(RemoteAgentClient.class));
    }
}
