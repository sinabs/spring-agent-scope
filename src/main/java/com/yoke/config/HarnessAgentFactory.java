package com.yoke.config;

import java.nio.file.Path;
import java.util.List;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import lombok.RequiredArgsConstructor;

/**
 * 每次调用 {@link #create()} 都新建一个 HarnessAgent，调用方用完负责 {@code close()}。
 *
 * <p>为什么不像 ReActAgent 那样共享一个 Builder：AgentScope 的 {@code HarnessAgent.Builder.build()}
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
    /** 共享的 Toolkit 模板，里面是 Yoke 自己的工具和 MCP 工具；启动后不再修改，Harness 的 build() 会先拷贝再往副本里加内置工具。 */
    private final Toolkit toolkit;
    private final List<SubagentDeclaration> subagents;

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
                // 共享同一个模板是安全的：AgentScope 的 build() 先 toolkit.copy() 再注册内置工具，模板不会被污染（见 SharedToolkitTests）
                .toolkit(toolkit)
                .subagents(subagents)
                // 权限取舍：对外服务的 Agent 不给读写文件和执行命令的能力
                .disableFilesystemTools()
                .disableShellTool()
                // 联网工具两道防线并用：disableWebTools 管主 Agent（按开关，框架改名也有效），
                // ToolsConfig.deny 管所有层——子 Agent 只继承文件和 shell 的关闭、不继承 web 的关闭
                // （AgentScope 的 HarnessAgentBuilderSupport 只传这两个），会自己注册 web_fetch / web_search，只能靠 deny 按名字移除，
                // 它会同时传给内置的 general-purpose 和所有声明的子 Agent。
                // 代价：主 Agent 构建时 web 工具未注册，ToolFilter 对 deny 里的名字每次打 WARN（实测两条），在 application.yml 里调低了级别。
                // 显式传 toolsConfig 还让 AgentScope 不再去读工作区里的 tools.json（里面能声明 stdio MCP 服务）
                .disableWebTools()
                .toolsConfig(YokeSubagents.toolsConfig())
                .build();
    }

    /**
     * 只按条数触发压缩。{@code triggerTokens} 在 AgentScope 里 0 表示「按模型上下文窗口动态计算」而不是关闭，
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
