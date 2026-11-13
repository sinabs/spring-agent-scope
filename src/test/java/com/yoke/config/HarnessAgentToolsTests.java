package com.yoke.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.yoke.mcp.McpConnections;
import com.yoke.support.RedisIntegrationTest;
import com.yoke.support.StubModelConfiguration;
import com.yoke.tool.SharedToolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工厂新建的 Agent 实际挂了哪些工具：用真实的 Redis 和完整上下文，工作区里预先放一份会拒掉天气工具的 {@code tools.json}，
 * 验证显式传入的 toolsConfig 让它不生效。工作区是本类独占的临时目录，不污染共享的测试工作区。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(StubModelConfiguration.class)
class HarnessAgentToolsTests extends RedisIntegrationTest {

    private static final Path WORKSPACE = workspaceWithToolsJson();

    @Autowired
    HarnessAgentFactory agentFactory;

    @Autowired
    SharedToolkit sharedToolkit;

    /** 不显式传 toolsConfig 时，这份 tools.json 会把 get_weather 拒掉。 */
    private static Path workspaceWithToolsJson() {
        try {
            Path workspace = Files.createTempDirectory("yoke-tools-json");
            Files.writeString(workspace.resolve("tools.json"), "{\"deny\":[\"get_weather\"]}");
            return workspace;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void workspaceProperty(DynamicPropertyRegistry registry) {
        registry.add("yoke.workspace", WORKSPACE::toString);
    }

    @Test
    void 模型只能看到Yoke自己的工具和必要的框架工具() {
        // 精确集合：读类的记忆和会话工具能跨用户读数据，联网工具能把请求引到外部，多出任何一个都要变红
        try (HarnessAgent agent = agentFactory.create()) {
            assertThat(agent.getToolkit().getToolNames())
                    .containsExactlyInAnyOrder(
                            "get_weather", "get_current_time", "calculate", "list_my_notes", "retrieve",
                            "memory_save", "load_skill_through_path", "wait_async_results",
                            "agent_spawn", "agent_send", "agent_list", "task_output", "task_cancel", "task_list");
        }
    }

    @Test
    void 工作区里的toolsJson不生效() {
        try (HarnessAgent agent = agentFactory.create()) {
            assertThat(agent.getToolkit().getToolNames()).contains("get_weather");
        }
    }

    @Test
    void Harness在build时加的内置工具都登记在MCP重名检查的清单里() {
        // MCP 重名检查在启动时只看得到模板，模板之外的工具靠 HARNESS_TOOL_NAMES 兜住；框架新增了内置工具，这里会变红
        try (HarnessAgent agent = agentFactory.create()) {
            assertThat(agent.getToolkit().getToolNames())
                    .filteredOn(name -> !sharedToolkit.toolkit().getToolNames().contains(name))
                    .isNotEmpty()
                    .allMatch(McpConnections.HARNESS_TOOL_NAMES::contains);
        }
    }
}
