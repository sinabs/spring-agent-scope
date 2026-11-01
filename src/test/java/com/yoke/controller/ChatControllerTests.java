package com.yoke.controller;

import java.util.ArrayList;
import java.util.List;

import com.yoke.model.ChatEvent;
import com.yoke.model.ChatRequest;
import com.yoke.model.ChatResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import org.springframework.http.codec.ServerSentEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private static Flux<AgentEvent> frameworkEvents(String... deltas) {
        List<AgentEvent> events = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (String delta : deltas) {
            events.add(new TextBlockDeltaEvent("r", "text", delta));
            all.append(delta);
        }
        events.add(new AgentResultEvent(new AssistantMessage(all.toString())));
        events.add(new AgentEndEvent("r"));
        return Flux.fromIterable(events);
    }

    @Test
    void 流式接口按顺序推送事件并以结束事件收尾() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(frameworkEvents("你", "好"));

        List<ServerSentEvent<ChatEvent>> events = controller.stream(new ChatRequest("s-1", "hi")).collectList().block();

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("textDelta", "textDelta", "done");
        assertThat(events.getLast().data()).isEqualTo(new ChatEvent.Done("s-1", "你好", new ChatEvent.Usage(0, 0, 0)));
        ArgumentCaptor<RuntimeContext> context = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).streamEvents(anyList(), context.capture());
        assertThat(context.getValue().getSessionId()).isEqualTo("s-1");
    }

    @Test
    void 流式接口缺少会话ID时服务端生成并出现在结束事件里() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(frameworkEvents("ok"));

        List<ServerSentEvent<ChatEvent>> events = controller.stream(new ChatRequest("  ", "hi")).collectList().block();

        ChatEvent.Done done = (ChatEvent.Done) events.getLast().data();
        assertThat(done.sessionId()).isNotBlank().isNotEqualTo("  ");
    }

    @Test
    void 流式接口正常结束后关闭Agent() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(frameworkEvents("ok"));

        controller.stream(new ChatRequest("s-1", "hi")).blockLast();

        verify(agent).close();
    }

    @Test
    void 流式接口出错时推送error事件并关闭Agent() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.<AgentEvent>just(new TextBlockDeltaEvent("r", "text", "半"))
                        .concatWith(Flux.error(new IllegalStateException("模型不可用"))));

        List<ServerSentEvent<ChatEvent>> events = controller.stream(new ChatRequest("s-1", "hi")).collectList().block();

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("textDelta", "error");
        assertThat(events.getLast().data()).isEqualTo(new ChatEvent.Error("模型不可用"));
        verify(agent).close();
    }

    @Test
    void 流式接口被取消时也关闭Agent() {
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(sink.asFlux());

        Disposable subscription = controller.stream(new ChatRequest("s-1", "hi")).subscribe();
        verify(agent, never()).close();
        subscription.dispose();

        verify(agent).close();
    }
}
