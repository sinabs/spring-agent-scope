package com.yoke.mcp;

import com.yoke.config.KnowledgeConfiguration;
import com.yoke.config.ToolConfiguration;
import com.yoke.tool.SharedToolkit;
import com.yoke.config.YokeProperties;
import com.yoke.support.McpTestServer;
import com.yoke.tool.WeatherTool;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** 从配置到容器的整条启动链路：只允许 HTTP 传输，默认不接任何 MCP，连不上就启动失败。 */
class McpStartupTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(YokeProperties.class)
    @Import({ToolConfiguration.class, KnowledgeConfiguration.class, WeatherTool.class})
    static class Context {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Context.class)
            .withPropertyValues("yoke.redis.uri=redis://127.0.0.1:6379", "yoke.workspace=/tmp/yoke");

    @Test
    void 默认不配置任何MCP服务_应用正常启动且工具里没有MCP工具() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(McpConnections.class).names()).isEmpty();
            assertThat(context.getBean(SharedToolkit.class).toolkit().getToolNames())
                    .containsExactlyInAnyOrder("get_weather", "get_current_time", "calculate", "list_my_notes", "retrieve");
        });
    }

    @Test
    void 配置stdio传输时应用启动即失败() {
        runner.withPropertyValues(
                        "yoke.mcp.servers[0].name=local-fs",
                        "yoke.mcp.servers[0].transport=stdio",
                        "yoke.mcp.servers[0].url=http://unused")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("只支持 HTTP 传输")
                            .hasStackTraceContaining("stdio");
                });
    }

    @Test
    void 服务连不上时应用启动即失败() {
        runner.withPropertyValues(
                        "yoke.mcp.servers[0].name=down",
                        "yoke.mcp.servers[0].transport=streamable-http",
                        "yoke.mcp.servers[0].url=http://127.0.0.1:1/mcp",
                        "yoke.mcp.servers[0].timeout=2s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("无法连接 MCP 服务");
                });
    }

    @Test
    void 配置了可用的HTTP服务时其工具出现在每个请求的Toolkit里() {
        try (McpTestServer server = new McpTestServer()) {
            runner.withPropertyValues(
                            "yoke.mcp.servers[0].name=demo",
                            "yoke.mcp.servers[0].transport=streamable-http",
                            "yoke.mcp.servers[0].url=" + server.url())
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        // 服务端公布的原名，不加前缀
                        assertThat(context.getBean(SharedToolkit.class).toolkit().getToolNames())
                                .contains("echo").doesNotContain("write_note");
                        assertThat(context.getBean(McpConnections.class).names()).containsExactly("demo");
                    });
        }
    }
}
