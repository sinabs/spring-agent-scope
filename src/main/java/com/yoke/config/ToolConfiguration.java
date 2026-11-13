package com.yoke.config;

import com.yoke.knowledge.RetrievePort;
import com.yoke.mcp.McpConnections;
import com.yoke.tool.SharedToolkit;
import com.yoke.tool.ChatTools;
import com.yoke.tool.RetrieveTool;
import com.yoke.tool.WeatherTool;
import io.agentscope.core.tool.Toolkit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具装配：Yoke 自己的工具（含天气）、检索工具和外部 MCP 工具汇总到一个 Toolkit 模板里。
 *
 * <p>这些工具类都是无状态的，可以安全地被所有请求的 Toolkit 副本共享。
 * 没有配置 MCP 服务时 {@link McpConnections} 是空的，不影响启动。
 */
@Configuration(proxyBeanMethods = false)
public class ToolConfiguration {

    @Bean
    public ChatTools chatTools() {
        return new ChatTools();
    }

    /** 没有 {@link RetrievePort} 时检索工具仍然注册，调用时返回「未配置」的提示语，而不是让工具凭空消失。 */
    @Bean
    public RetrieveTool retrieveTool(ObjectProvider<RetrievePort> port) {
        return new RetrieveTool(port.getIfAvailable());
    }

    @Bean(destroyMethod = "close")
    public McpConnections mcpConnections(YokeProperties yoke) {
        return McpConnections.connect(yoke.mcp().servers());
    }

    @Bean
    public SharedToolkit sharedToolkit(
            ChatTools chatTools, WeatherTool weatherTool, RetrieveTool retrieveTool, McpConnections mcp) {
        Toolkit template = new Toolkit();
        template.registerTool(chatTools);
        template.registerTool(weatherTool);
        template.registerTool(retrieveTool);
        mcp.registerInto(template);
        return new SharedToolkit(template);
    }
}
