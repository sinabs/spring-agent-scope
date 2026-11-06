package com.yoke.config;

import java.nio.file.Path;
import java.util.Map;

import com.yoke.controller.ChatController;
import com.yoke.model.ChatRequest;
import com.yoke.support.AguiTestSupport;
import com.yoke.support.ScriptedModel;
import com.yoke.support.TestAgents;
import com.yoke.tool.ChatTools;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** 子 Agent 里调用依赖 UserContext 的工具时，上下文有没有一路传下去。 */
class SubagentContextTests {

    @Test
    void 通用子Agent里调用list_my_notes能拿到发起请求的用户(@TempDir Path workspace) {
        ScriptedModel model = new ScriptedModel(call -> {
            if (call.systemText().startsWith("You are a memory")) {
                return ScriptedModel.text("[]");
            }
            boolean parent = call.systemText().startsWith("你是测试助手");
            if (parent) {
                return call.hasToolResult() ? ScriptedModel.text("主 Agent 收尾")
                        : ScriptedModel.toolUse("t1", "agent_spawn", Map.of("agent_id", "general-purpose", "task", "查笔记"));
            }
            return call.hasToolResult() ? ScriptedModel.text("子：" + call.lastToolResultText())
                    : ScriptedModel.toolUse("t2", "list_my_notes", Map.of());
        });
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ChatTools());
        ChatController controller = AguiTestSupport.controller(TestAgents.factory(model, workspace, toolkit));

        controller.chat(new ChatRequest("sub-ctx", "让子 Agent 查我的笔记", "alice")).block();

        String subReply = model.calls.stream().filter(call -> !call.systemText().startsWith("你是测试助手"))
                .filter(call -> call.hasToolResult()).map(ScriptedModel.Call::lastToolResultText).findFirst().orElseThrow();
        assertThat(subReply).contains("alice");
    }
}
