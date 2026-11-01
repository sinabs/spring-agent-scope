package com.yoke.config;

import java.nio.file.Path;
import java.util.List;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
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
 * 工具来自 {@link SharedToolkit}（见 {@link ToolConfiguration}），子 Agent 见 {@link YokeSubagents}。
 *
 * <p>容器里所有 {@link MiddlewareBase} Bean 都会按顺序挂到每个新建的 Agent 上，一个都没有也不影响启动。
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {

    @Bean
    public HarnessAgentFactory harnessAgentFactory(
            Model model,
            DistributedStore distributedStore,
            AgentscopeProperties properties,
            YokeProperties yoke,
            SharedToolkit toolkit,
            ObjectProvider<MiddlewareBase> middlewares) {
        AgentProperties agent = properties.getAgent();
        Path workspace = Path.of(yoke.workspace());
        // 子 Agent：代码里声明的 + 工作区文件声明的，id 不许重复；文件在启动时拷进工作区，由 Harness 从那里加载
        List<SubagentDeclaration> subagents = YokeSubagents.programmatic();
        YokeSubagents.prepareWorkspace(workspace, subagents);
        return new HarnessAgentFactory(
                agent.getName(),
                agent.getSysPrompt(),
                agent.getMaxIters(),
                model,
                distributedStore,
                workspace,
                yoke.compaction(),
                middlewares.orderedStream().toList(),
                toolkit.toolkit(),
                subagents);
    }
}
