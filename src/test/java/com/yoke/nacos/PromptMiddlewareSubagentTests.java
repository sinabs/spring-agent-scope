package com.yoke.nacos;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.yoke.config.HarnessAgentFactory;
import com.yoke.config.YokeProperties;
import com.yoke.config.YokeSubagents;
import com.yoke.support.ScriptedModel;
import com.yoke.support.TestAgents;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探明一件事：挂在主 Agent 上的 {@code onSystemPrompt} 中间件（Prompt 热更新就是这种）会不会被子 Agent 继承，
 * 也就是业务提示词会不会出现在子 Agent 的系统提示词里。用一个追加固定标记的同类中间件和脚本模型实测。
 */
class PromptMiddlewareSubagentTests {

    private static final String MARK = "【来自中间件的标记】";
    private static final String PARENT_PROMPT = "你是测试助手";

    @Test
    void 实测_主Agent的系统提示词中间件是否出现在子Agent的系统提示词里(@TempDir Path workspace) {
        MiddlewareBase appendMark = new MiddlewareBase() {
            @Override
            public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
                return Mono.just(currentPrompt + "\n\n" + MARK);
            }
        };
        ScriptedModel model = new ScriptedModel(call -> {
            if (call.systemText().startsWith("You are a memory")) {
                return ScriptedModel.text("[]");
            }
            if (!call.systemText().startsWith(PARENT_PROMPT)) {
                return ScriptedModel.text("子 Agent 的回复");
            }
            if (!call.hasToolResult() && !call.tools().isEmpty()) {
                return ScriptedModel.toolUse("t1", "agent_spawn", Map.of("agent_id", "summarizer", "task", "做点事"));
            }
            return ScriptedModel.text("主 Agent 收尾");
        });
        var subagents = YokeSubagents.programmatic();
        YokeSubagents.prepareWorkspace(workspace, subagents);
        HarnessAgentFactory factory = new HarnessAgentFactory("Yoke", PARENT_PROMPT, 6, model, TestAgents.inMemoryStore(),
                workspace, new YokeProperties.Compaction(30, 10), List.of(appendMark), new Toolkit(), subagents);

        try (var agent = factory.create()) {
            agent.call("hi", RuntimeContext.builder().userId("u").sessionId("s-sub").build()).block();
        }

        var parentCalls = model.calls.stream().filter(c -> c.systemText().startsWith(PARENT_PROMPT)).toList();
        var subCalls = model.calls.stream()
                .filter(c -> !c.systemText().startsWith(PARENT_PROMPT) && !c.systemText().startsWith("You are a memory")).toList();
        assertThat(parentCalls).as("主 Agent 的系统提示词带标记").isNotEmpty().allMatch(c -> c.systemText().contains(MARK));
        assertThat(subCalls).as("子 Agent 确实被调用了").isNotEmpty();
        System.out.println("SUBAGENT-INHERITS-SYSTEM-PROMPT-MIDDLEWARE=" + subCalls.stream().allMatch(c -> c.systemText().contains(MARK)));
        // 结论固化成断言，框架行为变化时这里会提醒
        assertThat(subCalls).allMatch(c -> c.systemText().contains(MARK));
    }
}
