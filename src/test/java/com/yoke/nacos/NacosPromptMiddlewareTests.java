package com.yoke.nacos;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.alibaba.nacos.api.exception.NacosException;
import com.yoke.config.HarnessAgentFactory;
import com.yoke.config.YokeProperties;
import com.yoke.config.YokeSubagents;
import com.yoke.support.ScriptedModel;
import com.yoke.support.TestAgents;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NacosPromptMiddlewareTests {

    private static NacosPromptMiddleware middlewareReturning(String template) throws NacosException {
        AiService ai = mock(AiService.class);
        when(ai.getPrompt("k")).thenReturn(new Prompt("k", "1.0.0", template));
        return new NacosPromptMiddleware(ai, "k", Duration.ofSeconds(2));
    }

    private static String apply(NacosPromptMiddleware middleware, String current) {
        return middleware.onSystemPrompt(null, null, current).block();
    }

    @Test
    void 最新提示词追加在内置提示词之后() throws Exception {
        String result = apply(middlewareReturning("请用海盗口吻回答"), "你是助手\n");

        assertThat(result).isEqualTo("你是助手\n\n请用海盗口吻回答");
    }

    @Test
    void 重复调用不会重复拼接() throws Exception {
        NacosPromptMiddleware middleware = middlewareReturning("请用海盗口吻回答");

        String once = apply(middleware, "你是助手");
        String twice = apply(middleware, once);

        assertThat(twice).isEqualTo(once);
        assertThat(twice.split("请用海盗口吻回答", -1)).hasSize(2);
    }

    @Test
    void 拉取失败时原样返回当前提示词() throws Exception {
        AiService ai = mock(AiService.class);
        when(ai.getPrompt("k")).thenThrow(new NacosException(NacosException.SERVER_ERROR, "boom 带着地址 10.0.0.1"));

        assertThat(apply(new NacosPromptMiddleware(ai, "k", Duration.ofSeconds(2)), "内置提示词")).isEqualTo("内置提示词");
    }

    @Test
    void Prompt不存在或为空白时原样返回() throws Exception {
        AiService missing = mock(AiService.class);
        when(missing.getPrompt("k")).thenThrow(new NacosException(NacosException.NOT_FOUND, "prompt not found"));

        assertThat(apply(new NacosPromptMiddleware(missing, "k", Duration.ofSeconds(2)), "内置提示词")).isEqualTo("内置提示词");
        assertThat(apply(middlewareReturning("   \n "), "内置提示词")).isEqualTo("内置提示词");
        assertThat(apply(middlewareReturning(null), "内置提示词")).isEqualTo("内置提示词");
    }

    @Test
    void 内置提示词为空时只返回Nacos里的内容() throws Exception {
        assertThat(apply(middlewareReturning("只有这一段"), "")).isEqualTo("只有这一段");
    }

    @Test
    void Nacos不响应时在超时后兜底_对话仍然成功(@TempDir Path workspace) throws Exception {
        AiService hanging = mock(AiService.class);
        when(hanging.getPrompt("k")).thenAnswer(_ -> {
            Thread.sleep(10_000);
            return null;
        });
        NacosPromptMiddleware middleware = new NacosPromptMiddleware(hanging, "k", Duration.ofMillis(300));
        ScriptedModel model = new ScriptedModel(_ -> ScriptedModel.text("照常回答"));
        var subagents = YokeSubagents.programmatic();
        YokeSubagents.prepareWorkspace(workspace, subagents);
        HarnessAgentFactory factory = new HarnessAgentFactory("Yoke", "内置提示词", 6, model, TestAgents.inMemoryStore(),
                workspace, new YokeProperties.Compaction(30, 10), List.of(middleware), new Toolkit(), subagents);

        long start = System.nanoTime();
        String reply;
        try (var agent = factory.create()) {
            reply = agent.call("你好", RuntimeContext.builder().userId("u").sessionId("s-timeout").build()).blockOptional().orElseThrow().getTextContent();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(reply).isEqualTo("照常回答");
        assertThat(elapsedMillis).as("没有被 10 秒的无响应拖住").isLessThan(5_000);
        assertThat(model.calls.getFirst().systemText()).contains("内置提示词");
    }

    @Test
    void Prompt没有内容_getTemplate为null_也走兜底而不是抛异常() throws Exception {
        AiService ai = mock(AiService.class);
        when(ai.getPrompt("k")).thenReturn(new Prompt("k", "1.0.0", null));

        assertThat(apply(new NacosPromptMiddleware(ai, "k", Duration.ofSeconds(2)), "内置提示词")).isEqualTo("内置提示词");
    }
}
