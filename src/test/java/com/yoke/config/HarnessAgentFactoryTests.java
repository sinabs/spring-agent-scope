package com.yoke.config;

import com.yoke.tool.WeatherTool;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HarnessAgentFactoryTests {

    @Configuration(proxyBeanMethods = false)
    @Import({WeatherTool.class, HarnessAgentFactory.class})
    static class Base {

        @Bean
        Model model() {
            return mock(Model.class);
        }

        @Bean
        DistributedStore distributedStore() {
            return mock(DistributedStore.class);
        }

        @Bean
        AgentscopeProperties agentscopeProperties() {
            return new AgentscopeProperties();
        }

        @Bean
        YokeProperties yokeProperties() {
            return new YokeProperties(new YokeProperties.Redis("redis://127.0.0.1:6379", "yoke:"),
                    new YokeProperties.Compaction(30, 10), "/tmp/yoke-test");
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Base.class);

    @Test
    void 容器里没有任何中间件也能启动() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(HarnessAgentFactory.class);
        });
    }

    @Test
    void 有中间件Bean时也能启动() {
        runner.withBean(MiddlewareBase.class, () -> new MiddlewareBase() { })
                .run(context -> assertThat(context).hasNotFailed());
    }
}
