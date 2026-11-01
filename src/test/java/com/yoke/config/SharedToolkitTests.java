package com.yoke.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.yoke.support.ScriptedModel;
import com.yoke.support.TestAgents;
import com.yoke.tool.ChatTools;
import com.yoke.tool.RetrieveTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住「所有请求共用同一个 Toolkit 模板」这个前提：它成立靠的是 2.0.4 的 HarnessAgent.Builder.build() 会先拷贝 Toolkit。
 * 将来框架升级后如果不再拷贝，Harness 的内置工具会写进模板，这里会失败。
 */
class SharedToolkitTests {

    private static Toolkit template() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ChatTools());
        toolkit.registerTool(new RetrieveTool(null));
        return toolkit;
    }

    private static void chat(HarnessAgentFactory factory, String session) {
        try (HarnessAgent agent = factory.create()) {
            agent.call("hi", RuntimeContext.builder().userId("u").sessionId(session).build()).block();
        }
    }

    @Test
    void 连续多次请求之后模板里仍然只有Yoke自己的工具(@TempDir Path workspace) {
        Toolkit template = template();
        Set<String> before = new TreeSet<>(template.getToolNames());
        ScriptedModel model = new ScriptedModel(call -> ScriptedModel.text("ok"));
        HarnessAgentFactory factory = TestAgents.factory(model, workspace, template);

        for (int i = 0; i < 3; i++) {
            chat(factory, "s" + i);
        }

        assertThat(before).containsExactlyInAnyOrder("get_current_time", "calculate", "list_my_notes", "retrieve");
        assertThat(new TreeSet<>(template.getToolNames())).as("Harness 的内置工具没有写进共享模板").isEqualTo(before);
        List<Set<String>> perCall = model.calls.stream().map(ScriptedModel.Call::toolNames)
                .filter(names -> !names.isEmpty()).<Set<String>>map(TreeSet::new).toList();
        assertThat(perCall).hasSize(3);
        assertThat(perCall.get(1)).isEqualTo(perCall.get(0));
        assertThat(perCall.get(2)).isEqualTo(perCall.get(0));
        assertThat(perCall.get(0)).contains("agent_spawn", "memory_save", "calculate");
    }

    @Test
    void 并发地用同一个模板构建Agent_每个Agent看到的工具清单都完整一致(@TempDir Path workspace) throws Exception {
        ScriptedModel model = new ScriptedModel(call -> ScriptedModel.text("ok"));
        HarnessAgentFactory factory = TestAgents.factory(model, workspace, template());
        int workers = 16;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                String session = "c" + i;
                jobs.add(() -> {
                    chat(factory, session);
                    return null;
                });
            }
            for (var future : executor.invokeAll(jobs, 120, TimeUnit.SECONDS)) {
                future.get();
            }
        }

        List<Set<String>> perCall = model.calls.stream().map(ScriptedModel.Call::toolNames)
                .filter(names -> !names.isEmpty()).<Set<String>>map(TreeSet::new).toList();
        assertThat(perCall).hasSize(workers);
        assertThat(perCall).allSatisfy(tools -> assertThat(tools).isEqualTo(perCall.getFirst()));
    }
}
