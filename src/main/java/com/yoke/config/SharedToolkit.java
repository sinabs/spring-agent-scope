package com.yoke.config;

import io.agentscope.core.tool.Toolkit;

/**
 * 启动时装配好的 Toolkit 模板（Yoke 自己的工具加 MCP 工具），所有请求的 Agent 共用这一个。
 *
 * <p>能共用，依据是 AgentScope 的源码：{@code HarnessAgent.Builder.build()} 一上来就 {@code toolkit.copy()}，
 * Harness 自己的内置工具（记忆、会话、子 Agent 等）注册在副本上，传进去的模板不会被污染；
 * {@code Toolkit} 内部的注册表用的是 ConcurrentHashMap，只读的 {@code copy()} 可以并发。
 * 前提是<b>模板在启动之后不再被修改</b>，并且里面的工具实例无状态、线程安全（副本按引用共享工具实例）。
 * 官方 starter 把 Toolkit 注册成原型作用域 Bean，是因为它的 ReActAgent 直接使用传入的 Toolkit、不做拷贝。
 *
 * <p>单独包一层类型，是为了不和官方 starter 注册的原型 {@code Toolkit} Bean 在按类型注入时撞车。
 * MCP 连接由 {@code McpConnections} 持有，模板和副本都不拥有连接。
 */
public final class SharedToolkit {

    private final Toolkit toolkit;

    public SharedToolkit(Toolkit toolkit) {
        this.toolkit = toolkit;
    }

    public Toolkit toolkit() {
        return toolkit;
    }
}
