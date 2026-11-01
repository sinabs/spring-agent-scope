package com.yoke.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yoke.support.ScriptedModel;
import com.yoke.support.TestAgents;
import com.yoke.tool.ChatTools;
import com.yoke.tool.RetrieveTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** 实测 ToolFilter 对「deny/allow 里尚未注册的工具名」打的 WARN，用 logback 内存 appender 抓取。 */
class ToolFilterWarnTests {

    private static List<String> warnsDuring(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger("io.agentscope.harness.agent.tools.ToolFilter");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        // 同一个 JVM 里别的 Spring 测试会按 application.yml 把这个 logger 调成 ERROR，这里显式调回 WARN 再恢复
        Level original = logger.getLevel();
        logger.setLevel(Level.WARN);
        try {
            action.run();
        } finally {
            logger.setLevel(original);
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static final String DENY_WEB_SEARCH = "tools.json 'deny' references unknown tool 'web_search' (not currently registered).";
    private static final String DENY_WEB_FETCH = "tools.json 'deny' references unknown tool 'web_fetch' (not currently registered).";

    private List<String> warnsWhenDelegatingTo(String agentId, Path workspace) {
        ScriptedModel model = new ScriptedModel(call -> {
            if (call.systemText().startsWith("You are a memory")) {
                return ScriptedModel.text("[]");
            }
            if (call.systemText().startsWith("你是测试助手")) {
                return call.hasToolResult() ? ScriptedModel.text("收尾")
                        : ScriptedModel.toolUse("t1", "agent_spawn", Map.of("agent_id", agentId, "task", "x"));
            }
            return ScriptedModel.text("子回复");
        });
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ChatTools());
        toolkit.registerTool(new RetrieveTool(null));
        return warnsDuring(() -> {
            try (HarnessAgent agent = TestAgents.factory(model, workspace, toolkit).create()) {
                agent.call("hi", RuntimeContext.builder().userId("u").sessionId("s-" + agentId).build()).block();
            }
        });
    }

    /**
     * 同时使用 disableWebTools() 和 deny 的代价：主 Agent 构建时 web 工具根本没注册，ToolFilter 对 deny 里的两个名字各打一条 WARN。
     * 子 Agent 自己会注册 web 工具再被 deny 移除，不触发。这里把这个代价固化下来（实测）；
     * application.yml 把这个 logger 的级别调到 ERROR 以免每个请求两条噪音。
     */
    @Test
    void 主Agent构建时每次产生两条deny未注册的警告_子Agent构建不产生(@TempDir Path workspace) {
        assertThat(warnsWhenDelegatingTo("general-purpose", workspace)).containsExactlyInAnyOrder(DENY_WEB_SEARCH, DENY_WEB_FETCH);
        assertThat(warnsWhenDelegatingTo("kb-researcher", workspace)).containsExactlyInAnyOrder(DENY_WEB_SEARCH, DENY_WEB_FETCH);
    }

    @Test
    void 摘要子Agent的占位名走allow分支_每次派生多一条警告(@TempDir Path workspace) {
        assertThat(warnsWhenDelegatingTo("summarizer", workspace)).containsExactlyInAnyOrder(DENY_WEB_SEARCH, DENY_WEB_FETCH,
                "tools.json 'allow' references unknown tool '__no_tools__' (not currently registered).");
    }
}
