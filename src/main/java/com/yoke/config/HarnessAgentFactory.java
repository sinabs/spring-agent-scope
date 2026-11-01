package com.yoke.config;

import java.nio.file.Path;
import java.util.List;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.RequiredArgsConstructor;

/**
 * 每次调用 {@link #create()} 都新建一个 HarnessAgent，调用方用完负责 {@code close()}。
 *
 * <p>为什么不像 ReActAgent 那样共享一个 Builder：2.0.4 的 {@code HarnessAgent.Builder.build()}
 * 会往内部持有的 ReActAgent.Builder 里追加一批装配期中间件，同一个 Builder 重复 build，
 * 中间件会越积越多（实测每次多 11 个），所以这里只共享不可变的部分（模型、存储、配置、中间件实例），
 * Builder 每次现建。
 */
@RequiredArgsConstructor
public class HarnessAgentFactory {

    private final String name;
    private final String sysPrompt;
    private final int maxIters;
    private final Model model;
    private final DistributedStore distributedStore;
    private final Path workspace;
    private final YokeProperties.Compaction compaction;
    private final List<MiddlewareBase> middlewares;

    public HarnessAgent create() {
        return HarnessAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .maxIters(maxIters)
                .model(model)
                .workspace(workspace)
                // 会话状态由它提供：没有显式 stateStore 时，Harness 用 distributedStore 的 AgentStateStore
                .distributedStore(distributedStore)
                .compaction(compactionConfig())
                .middlewares(middlewares)
                // 权限取舍：对外服务的 Agent 不给读写文件和执行命令的能力，联网搜索同理
                .disableFilesystemTools()
                .disableShellTool()
                .disableWebTools()
                // 子 Agent 只继承文件和 shell 的关闭，不继承 web 的关闭（2.0.4 的 HarnessAgentBuilderSupport 只传这两个），
                // 开着就能让模型通过 agent_spawn 派生出带 web_fetch 的子 Agent 联网；第 03 轮专门引入子 Agent 时再按需打开
                .disableSubagents()
                .build();
    }

    /**
     * 只按条数触发压缩。{@code triggerTokens} 在 2.0.4 里 0 表示「按模型上下文窗口动态计算」而不是关闭，
     * 所以用 {@link Integer#MAX_VALUE} 让 token 阈值实际上永远不会先触发；
     * {@code keepTokens} 为 0 才会改用 keepMessages（默认 -1 是动态）。
     */
    CompactionConfig compactionConfig() {
        return CompactionConfig.builder()
                .triggerMessages(compaction.triggerMessages())
                .triggerTokens(Integer.MAX_VALUE)
                .keepTokens(0)
                .keepMessages(compaction.keepMessages())
                .build();
    }
}
