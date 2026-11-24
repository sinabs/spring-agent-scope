package com.yoke.support;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.yoke.config.HarnessAgentFactory;
import com.yoke.config.YokeProperties;
import com.yoke.config.YokeSubagents;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /**
     * 跟踪工厂创建出的全部 {@link HarnessAgent} 是否都已执行完 {@code close()}。
     *
     * <p>{@code ChatController} 和 {@code YokeAgentRunner} 都使用 {@code eager=false}：下游拿到完成或取消信号时，
     * {@code HarnessAgent.close()} 可能还在后台线程排空会话镜像与记忆文件写入。使用 {@code @TempDir} 的测试在
     * {@code @AfterEach} 中调用 {@link #awaitAllClosed()}，避免 JUnit 删除临时目录时与后台写入撞车。
     */
    public static final class CloseTracker {

        private final Phaser openAgents = new Phaser(1);

        public HarnessAgentFactory wrap(HarnessAgentFactory real) {
            HarnessAgentFactory proxy = mock(HarnessAgentFactory.class);
            when(proxy.create()).thenAnswer(_ -> {
                HarnessAgent agent = real.create();
                openAgents.register();
                HarnessAgent tracked = mock(HarnessAgent.class, delegatesTo(agent));
                AtomicBoolean closed = new AtomicBoolean();
                doAnswer(_ -> {
                    try {
                        agent.close();
                    } finally {
                        if (closed.compareAndSet(false, true)) {
                            openAgents.arriveAndDeregister();
                        }
                    }
                    return null;
                }).when(tracked).close();
                return tracked;
            });
            return proxy;
        }

        public void awaitAllClosed() throws InterruptedException, TimeoutException {
            int phase = openAgents.arrive();
            openAgents.awaitAdvanceInterruptibly(phase, 15, TimeUnit.SECONDS);
        }
    }
}
