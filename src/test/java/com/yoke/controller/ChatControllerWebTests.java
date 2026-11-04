package com.yoke.controller;

import com.yoke.config.AguiConfiguration;
import com.yoke.support.AguiTestSupport;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过 MockMvc 验证请求校验：同步接口缺少或空白的 message 由 @NotBlank 拦截，流式接口的请求体不合规由编解码层拦截，都返回 400。
 */
@WebMvcTest(ChatController.class)
@Import(AguiConfiguration.class)
class ChatControllerWebTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReActAgent.Builder agentBuilder;

    @MockitoBean
    ReActAgent agent;

    @BeforeEach
    void 让Builder返回模拟的Agent() {
        when(agentBuilder.build()).thenReturn(agent);
    }

    @Test
    void 缺少message时返回400() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void message为null时返回400() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s-1\",\"message\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void message为空串或纯空白时返回400() throws Exception {
        for (String message : new String[] {"", "  "}) {
            mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"" + message + "\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void 只有message时正常返回() throws Exception {
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("你好呀").build();
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(reply));

        var started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("你好呀"))
                .andExpect(jsonPath("$.sessionId").isNotEmpty());
    }

    @Test
    void sessionId不合规时返回400() throws Exception {
        for (String sessionId : new String[] {"user:42", "a/b", "../x", "..", "a b", "中文", "a".repeat(65)}) {
            mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"你好\"}"))
                    .andExpect(status().isBadRequest());
        }
        verify(agentBuilder, never()).build();
    }

    private static final String USER_HI = "[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"你好\"}]";

    @Test
    void 流式接口只接受JSON请求体() throws Exception {
        // text/plain 是不触发 CORS 预检的简单请求，必须拒绝，否则任何网页都能跨站发起对话
        mvc.perform(post("/api/chat/stream").contentType(MediaType.TEXT_PLAIN)
                        .content(AguiTestSupport.body("s-1", "你好")))
                .andExpect(status().isUnsupportedMediaType());
        verify(agentBuilder, never()).build();
    }

    @Test
    void 流式接口请求体不合法时返回400() throws Exception {
        String[] bodies = {
                "", "{", "[]", "{}",
                "{\"threadId\":\"s-1\",\"runId\":\"r-1\"}",
                "{\"threadId\":\"s-1\",\"runId\":\"r-1\",\"messages\":[]}",
                "{\"threadId\":\"s-1\",\"runId\":\"r-1\",\"messages\":[{\"id\":\"m1\",\"role\":\"assistant\",\"content\":\"hi\"}]}",
                "{\"threadId\":\"s-1\",\"runId\":\"r-1\",\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"  \"}]}",
                "{\"threadId\":\"../x\",\"runId\":\"r-1\",\"messages\":" + USER_HI + "}",
                "{\"messages\":" + USER_HI + "}"};
        for (String body : bodies) {
            mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        // 400 发生在建立流之前，不会创建 Agent
        verify(agentBuilder, never()).build();
    }

    private static Flux<AgentEvent> helloEvents() {
        return Flux.just(
                new AgentStartEvent("s-1", "r", "Yoke"),
                new TextBlockStartEvent("r", "text"),
                new TextBlockDeltaEvent("r", "text", "你"),
                new TextBlockDeltaEvent("r", "text", "好"),
                new TextBlockEndEvent("r", "text"),
                new AgentResultEvent(new AssistantMessage("你好")),
                new AgentEndEvent("r"));
    }

    @Test
    void 流式接口返回事件流且只有data行() throws Exception {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(helloEvents());

        var started = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content(AguiTestSupport.body("s-1", "你好")))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(UTF_8);

        assertThat(body).containsSubsequence(
                "data:{\"type\":\"RUN_STARTED\"", "\"threadId\":\"s-1\"",
                "data:{\"type\":\"TEXT_MESSAGE_START\"",
                "data:{\"type\":\"TEXT_MESSAGE_CONTENT\"", "\"delta\":\"你\"",
                "data:{\"type\":\"TEXT_MESSAGE_CONTENT\"", "\"delta\":\"好\"",
                "data:{\"type\":\"TEXT_MESSAGE_END\"",
                "data:{\"type\":\"RUN_FINISHED\"");
        // AG-UI 的类型在 JSON 里，不设 SSE 的 event 名
        assertThat(body).doesNotContain("event:");
    }

    @Test
    void 流式接口的响应类型带UTF8字符集() throws Exception {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(helloEvents());

        var started = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content(AguiTestSupport.body("s-1", "你好")))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 没有 charset 时 IDEA 的 HTTP Client 会按 ISO-8859-1 解码，中文显示成乱码
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsStringIgnoringCase("charset=UTF-8")));
    }
}
