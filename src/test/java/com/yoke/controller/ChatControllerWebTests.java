package com.yoke.controller;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
