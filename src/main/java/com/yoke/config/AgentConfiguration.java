package com.yoke.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.spring.boot.properties.AgentProperties;
import io.agentscope.spring.boot.properties.AgentscopeProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 的共享配置。
 *
 * <p>这里只配置 {@link ReActAgent.Builder}，不创建 Agent 实例：每次请求调用 {@code build()} 新建一个，
 * 用完关闭。因此官方 starter 的单例 Agent 在 application.yml 里关闭了（agentscope.agent.enabled=false），
 * 但名称、提示词、迭代次数仍沿用 agentscope.agent.* 的配置。
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {

    /**
     * 对话历史的共享存储。每次请求都是新的 Agent 实例，历史必须放在实例之外才能跨请求保留。
     *
     * <p>内存实现只适合单进程，重启即丢失；多副本部署时替换成 Redis 或 JDBC 实现即可，
     * 自定义的 {@link AgentStateStore} Bean 会让这里自动退让。
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentStateStore agentStateStore() {
        return new InMemoryAgentStateStore();
    }

    /**
     * 共享的 Agent Builder，在启动时配置完成，请求期间只负责 {@code build()}。
     * 用户、会话等每次请求不同的信息通过 RuntimeContext 传入，不会修改这个 Builder。
     */
    @Bean
    public ReActAgent.Builder agentBuilder(Model model, AgentStateStore stateStore, AgentscopeProperties properties) {
        AgentProperties agent = properties.getAgent();
        return ReActAgent.builder()
                .name(agent.getName())
                .sysPrompt(agent.getSysPrompt())
                .maxIters(agent.getMaxIters())
                .model(model)
                .stateStore(stateStore);
    }
}
