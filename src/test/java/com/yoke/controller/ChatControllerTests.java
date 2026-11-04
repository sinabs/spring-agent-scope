package com.yoke.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.yoke.codec.InvalidStreamRequestException;
import com.yoke.config.AguiConfiguration;
import com.yoke.support.AguiTestSupport;
import com.yoke.model.ChatRequest;
import com.yoke.model.ChatResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
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
    private final AguiConfiguration aguiConfiguration = new AguiConfiguration();
    private final ChatController controller = new ChatController(agentBuilder,
            aguiConfiguration.aguiStreamCodec(aguiConfiguration.aguiAdapterConfig()));

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
        events.add(new AgentStartEvent("s-1", "r", "Yoke"));
        events.add(new TextBlockStartEvent("r", "text"));
        StringBuilder all = new StringBuilder();
        for (String delta : deltas) {
            events.add(new TextBlockDeltaEvent("r", "text", delta));
            all.append(delta);
        }
        events.add(new TextBlockEndEvent("r", "text"));
        events.add(new AgentResultEvent(new AssistantMessage(all.toString())));
        events.add(new AgentEndEvent("r"));
        return Flux.fromIterable(events);
    }

    private List<String> streamData(String body) {
        return controller.stream(body).map(ServerSentEvent::data).collectList().block();
    }

    @Test
    void 流式接口按顺序推送AGUI事件并以RUN_FINISHED收尾() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(frameworkEvents("你", "好"));

        List<String> events = streamData(AguiTestSupport.body("s-1", "hi"));

        assertThat(AguiTestSupport.types(events)).containsExactly("RUN_STARTED", "TEXT_MESSAGE_START",
                "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED");
        ArgumentCaptor<RuntimeContext> context = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).streamEvents(anyList(), context.capture());
        assertThat(context.getValue().getSessionId()).isEqualTo("s-1");
        assertThat(context.getValue().getUserId()).isEqualTo("default");
    }

    @Test
    void 流式接口只把最后一条user消息交给Agent() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(frameworkEvents("ok"));
        String body = "{\"threadId\":\"s-1\",\"runId\":\"r-1\",\"messages\":["
                + "{\"id\":\"m1\",\"role\":\"user\",\"content\":\"第一句\"},"
                + "{\"id\":\"m2\",\"role\":\"assistant\",\"content\":\"回答\"},"
                + "{\"id\":\"m3\",\"role\":\"user\",\"content\":\"最后一句\"}],"
                + "\"tools\":[{\"name\":\"hack\",\"description\":\"d\",\"parameters\":{}}]}";

        streamData(body);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> messages = ArgumentCaptor.forClass(List.class);
        verify(agent).streamEvents(messages.capture(), any(RuntimeContext.class));
        assertThat(messages.getValue()).singleElement()
                .satisfies(msg -> assertThat(msg.getTextContent()).isEqualTo("最后一句"));
    }

    @Test
    void 请求不合法时在建立流之前抛出且不创建Agent() {
        assertThatThrownBy(() -> controller.stream("{\"threadId\":\"../x\",\"runId\":\"r-1\",\"messages\":[]}"))
                .isInstanceOf(InvalidStreamRequestException.class);

        verify(agentBuilder, never()).build();
    }

    @Test
    void 流式接口缺少runId时抛出且不创建Agent() {
        String body = "{\"threadId\":\"s-1\",\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"hi\"}]}";

        assertThatThrownBy(() -> controller.stream(body)).isInstanceOf(InvalidStreamRequestException.class);

        verify(agentBuilder, never()).build();
    }

    @Test
    void 流式接口正常结束后关闭Agent() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(frameworkEvents("ok"));

        controller.stream(AguiTestSupport.body("s-1", "hi")).blockLast();

        verify(agent).close();
    }

    @Test
    void 流式接口中途出错时推送RUN_ERROR并关闭Agent() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.<AgentEvent>just(new AgentStartEvent("s-1", "r", "Yoke"),
                                new TextBlockDeltaEvent("r", "text", "半"))
                        .concatWith(Flux.error(new IllegalStateException("模型不可用"))));

        List<String> events = streamData(AguiTestSupport.body("s-1", "hi"));

        assertThat(AguiTestSupport.types(events).getLast()).isEqualTo("RUN_ERROR");
        assertThat(AguiTestSupport.types(events)).doesNotContain("RUN_FINISHED");
        assertThat(AguiTestSupport.read(events.getLast())).containsEntry("message", "模型不可用");
        verify(agent).close();
    }

    @Test
    void 创建Agent失败时也以RUN_ERROR收尾() {
        when(agentBuilder.build()).thenThrow(new IllegalStateException("装配失败"));

        List<String> events = streamData(AguiTestSupport.body("s-1", "hi"));

        assertThat(events).hasSize(1);
        assertThat(AguiTestSupport.read(events.getFirst()))
                .containsEntry("type", "RUN_ERROR")
                .containsEntry("threadId", "s-1")
                .containsEntry("message", "装配失败");
    }

    @Test
    void 框架提示转成CUSTOM_hint事件() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(
                new AgentStartEvent("s-1", "r", "Yoke"),
                new HintBlockEvent("r", "hint-1", "iteration", "已接近最大迭代次数"),
                new AgentEndEvent("r")));

        List<String> events = streamData(AguiTestSupport.body("s-1", "hi"));

        assertThat(AguiTestSupport.types(events)).containsExactly("RUN_STARTED", "CUSTOM", "RUN_FINISHED");
        assertThat(AguiTestSupport.read(events.get(1)))
                .containsEntry("name", "hint")
                .containsEntry("value", Map.of("source", "iteration", "text", "已接近最大迭代次数"));
    }

    @Test
    void 流式接口被取消时也关闭Agent() {
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(sink.asFlux());

        Disposable subscription = controller.stream(AguiTestSupport.body("s-1", "hi")).subscribe();
        verify(agent, never()).close();
        subscription.dispose();

        verify(agent).close();
    }
}
