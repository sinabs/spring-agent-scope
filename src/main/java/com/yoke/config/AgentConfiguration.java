package com.yoke.config;

import java.nio.file.Path;
import com.yoke.tool.WeatherTool;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.spring.boot.properties.AgentProperties;
import io.agentscope.spring.boot.properties.AgentscopeProperties;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 的共享配置。
 *
 * <p>这里不创建 Agent 实例，只组装 {@link HarnessAgentFactory}：每次请求调用一次 {@code create()}
 * 新建一个 HarnessAgent，用完关闭。因此官方 starter 的单例 Agent 在 application.yml 里关闭了
 * （agentscope.agent.enabled=false），但名称、提示词、迭代次数仍沿用 agentscope.agent.* 的配置。
 *
 * <p>对话历史存在 {@link DistributedStore}（Redis）里，见 {@link RedisConfiguration}；
 * 容器里所有 {@link MiddlewareBase} Bean 都会按顺序挂到每个新建的 Agent 上，一个都没有也不影响启动。
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {

    /** 工具类被所有请求共享，必须无请求态字段。 */
    @Bean
    public WeatherTool weatherTool() {
        return new WeatherTool();
    }

    @Bean
    public HarnessAgentFactory harnessAgentFactory(
            Model model,
            DistributedStore distributedStore,
            AgentscopeProperties properties,
            YokeProperties yoke,
            WeatherTool weatherTool,
            ObjectProvider<MiddlewareBase> middlewares) {
        AgentProperties agent = properties.getAgent();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(weatherTool);
        return new HarnessAgentFactory(
                agent.getName(),
                agent.getSysPrompt(),
                agent.getMaxIters(),
                model,
                toolkit,
                distributedStore,
                Path.of(yoke.workspace()),
                yoke.compaction(),
                middlewares.orderedStream().toList());
    }
}
