package com.yoke.support;

import java.nio.file.Path;
import java.util.List;

import com.yoke.config.HarnessAgentFactory;
import com.yoke.config.YokeProperties;
import com.yoke.config.YokeSubagents;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;

/** 不依赖 Redis 的 Agent 装配：会话状态放内存，用来测试工具和子 Agent 的行为。 */
public final class TestAgents {

    private TestAgents() {
    }

    public static DistributedStore inMemoryStore() {
        return DistributedStore.builder()
                .agentStateStore(new InMemoryAgentStateStore())
                .baseStore(new InMemoryStore())
                .build();
    }

    /** 与线上同一个工厂，只把模型、存储、工具换成测试用的；子 Agent 声明文件会真实地装进工作区。 */
    public static HarnessAgentFactory factory(Model model, Path workspace, Toolkit toolkitTemplate) {
        var subagents = YokeSubagents.programmatic();
        YokeSubagents.prepareWorkspace(workspace, subagents);
        return new HarnessAgentFactory("Yoke", "你是测试助手", 6, model, inMemoryStore(), workspace,
                new YokeProperties.Compaction(30, 10), List.of(), toolkitTemplate, subagents);
    }
}
