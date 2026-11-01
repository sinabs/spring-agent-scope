package com.yoke.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 真实的装配入口 AgentConfiguration：工作区里已有同名声明文件时，应用上下文启动失败。 */
class AgentConfigurationConflictTests {

    private ApplicationContextRunner runner(Path workspace) {
        return new ApplicationContextRunner()
                .withBean(Model.class, () -> mock(Model.class))
                .withBean(DistributedStore.class, () -> mock(DistributedStore.class))
                .withBean(AgentscopeProperties.class, AgentscopeProperties::new)
                .withBean(SharedToolkit.class, () -> new SharedToolkit(new Toolkit()))
                .withBean(YokeProperties.class, () -> new YokeProperties(new YokeProperties.Redis("redis://127.0.0.1:6379", "yoke:"),
                        new YokeProperties.Compaction(30, 10), workspace.toString(),
                        new YokeProperties.Knowledge(new YokeProperties.Knowledge.Embedding(null, null, null, null)),
                        new YokeProperties.Mcp(List.of())))
                .withUserConfiguration(AgentConfiguration.class);
    }

    @Test
    void 工作区里遗留了同名的summarizer文件时启动失败(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(workspace.resolve("subagents/summarizer.md"), "---\ndescription: 遗留\n---\n旧提示词");

        runner(workspace).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("子 Agent id 重复").hasStackTraceContaining("summarizer");
        });
    }

    @Test
    void 工作区干净时正常启动并装好声明文件(@TempDir Path workspace) {
        runner(workspace).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(workspace.resolve("subagents/kb-researcher.md")).exists();
        });
    }
}
