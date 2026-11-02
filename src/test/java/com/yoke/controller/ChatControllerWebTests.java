package com.yoke.controller;

import com.yoke.config.HarnessAgentFactory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
 * 通过 MockMvc 验证请求校验：缺少或空白的 message 由 @NotBlank 拦截并返回 400。
 */
@WebMvcTest(ChatController.class)
class ChatControllerWebTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    HarnessAgentFactory agentFactory;

    @MockitoBean
    HarnessAgent agent;

    @BeforeEach
    void 让工厂返回模拟的Agent() {
        when(agentFactory.create()).thenReturn(agent);
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
    void 请求体里的userId会传给Agent() throws Exception {
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("你好呀").build();
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(reply));

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\",\"sessionId\":\"s-1\",\"message\":\"你好\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<RuntimeContext> context = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).call(anyList(), context.capture());
        assertThat(context.getValue().getUserId()).isEqualTo("alice");
    }

    @Test
    void 非法的userId或sessionId返回400() throws Exception {
        String[][] illegal = {
                {"userId", "a/b"}, {"userId", ".."}, {"userId", "."}, {"userId", "含中文"},
                {"userId", "x".repeat(65)}, {"userId", "a b"},
                {"sessionId", "b/c"}, {"sessionId", ".."}, {"sessionId", "../etc"}, {"sessionId", "x".repeat(65)}};
        for (String[] field : illegal) {
            mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"" + field[0] + "\":\"" + field[1] + "\",\"message\":\"你好\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void 流式接口同样拦截非法标识() throws Exception {
        mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"a/b\",\"message\":\"你好\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 合法标识和缺失_空白的标识都放行() throws Exception {
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("ok").build();
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(reply));

        String[] bodies = {
                "{\"userId\":\"Alice_1.x-y\",\"sessionId\":\"" + "s".repeat(64) + "\",\"message\":\"你好\"}",
                "{\"userId\":\"..a\",\"message\":\"你好\"}",
                "{\"userId\":\" \",\"sessionId\":\" \",\"message\":\"你好\"}",
                "{\"message\":\"你好\"}"};
        for (String body : bodies) {
            mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
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
    void 流式接口缺少或空白的message时返回400() throws Exception {
        for (String body : new String[] {"{}", "{\"message\":null}", "{\"message\":\"\"}", "{\"message\":\"  \"}"}) {
            mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void 流式接口返回事件流且事件名正确() throws Exception {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(
                new TextBlockDeltaEvent("r", "text", "你"),
                new TextBlockDeltaEvent("r", "text", "好"),
                new AgentResultEvent(new AssistantMessage("你好")),
                new AgentEndEvent("r")));

        var started = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s-1\",\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(UTF_8);

        assertThat(body).containsSubsequence(
                "event:textDelta", "data:{\"text\":\"你\"}",
                "event:textDelta", "data:{\"text\":\"好\"}",
                "event:done", "\"sessionId\":\"s-1\"", "\"text\":\"你好\"");
    }

    @Test
    void 流式接口的响应类型带UTF8字符集() throws Exception {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(
                new AgentResultEvent(new AssistantMessage("你好")),
                new AgentEndEvent("r")));

        var started = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s-1\",\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 没有 charset 时 IDEA 的 HTTP Client 会按 ISO-8859-1 解码，中文显示成乱码
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsStringIgnoringCase("charset=UTF-8")));
    }
}
