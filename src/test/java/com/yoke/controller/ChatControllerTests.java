package com.yoke.controller;

import java.util.List;

import com.yoke.model.ChatRequest;
import com.yoke.model.ChatResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTests {

    private final ReActAgent agent = mock(ReActAgent.class);
    private final ReActAgent.Builder agentBuilder = mock(ReActAgent.Builder.class);
    private final ChatController controller = new ChatController(agentBuilder);

    ChatControllerTests() {
        when(agentBuilder.build()).thenReturn(agent);
    }

    private static Msg reply(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    @Test
    void 带会话ID时沿用该会话() {
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(reply("你好呀")));

        ChatResponse response = controller.chat(new ChatRequest("s-1", "你好")).block();

        assertThat(response).isEqualTo(new ChatResponse("s-1", "你好呀"));
        ArgumentCaptor<RuntimeContext> context = ArgumentCaptor.forClass(RuntimeContext.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> messages = ArgumentCaptor.forClass(List.class);
        verify(agent).call(messages.capture(), context.capture());
        assertThat(context.getValue().getSessionId()).isEqualTo("s-1");
        assertThat(messages.getValue()).singleElement()
                .satisfies(msg -> assertThat(msg.getTextContent()).isEqualTo("你好"));
    }

    @Test
    void 没有会话ID时生成新的() {
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(reply("hello")));

        ChatResponse response = controller.chat(new ChatRequest(null, "hi")).block();

        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.reply()).isEqualTo("hello");
    }

    @Test
    void 会话ID为空白时也生成新的() {
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(reply("hello")));

        ChatResponse response = controller.chat(new ChatRequest("  ", "hi")).block();

        assertThat(response.sessionId()).isNotBlank().isNotEqualTo("  ");
    }

    @Test
    void 调用完成后关闭本次创建的Agent() {
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(reply("ok")));

        controller.chat(new ChatRequest("s-1", "hi")).block();

        verify(agent).close();
    }

    @Test
    void 调用失败时也关闭Agent() {
        when(agent.call(anyList(), any(RuntimeContext.class)))
                .thenReturn(Mono.error(new IllegalStateException("模型不可用")));

        assertThatThrownBy(() -> controller.chat(new ChatRequest("s-1", "hi")).block())
                .hasMessageContaining("模型不可用");

        verify(agent).close();
    }
}
