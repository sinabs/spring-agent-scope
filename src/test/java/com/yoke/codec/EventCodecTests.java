package com.yoke.codec;

import java.util.ArrayList;
import java.util.List;

import com.yoke.model.ChatEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventCodecTests {

    private static List<ChatEvent> decodeAll(EventCodec codec, AgentEvent... events) {
        List<ChatEvent> out = new ArrayList<>();
        for (AgentEvent event : events) {
            out.addAll(codec.decode(event));
        }
        return out;
    }

    private static ModelCallEndEvent usage(int in, int out) {
        return new ModelCallEndEvent("r", ChatUsage.builder().inputTokens(in).outputTokens(out).build());
    }

    @Test
    void 文本增量按到达顺序输出且起止事件被忽略() {
        List<ChatEvent> events = decodeAll(new EventCodec("s"),
                new ModelCallStartEvent("r"),
                new TextBlockStartEvent("r", "text"),
                new TextBlockDeltaEvent("r", "text", "你"),
                new TextBlockDeltaEvent("r", "text", "好"),
                new TextBlockEndEvent("r", "text"));

        assertThat(events).containsExactly(new ChatEvent.TextDelta("你"), new ChatEvent.TextDelta("好"));
    }

    @Test
    void 工具调用的碎片攒齐后才输出一个完整事件() {
        EventCodec codec = new EventCodec("s");

        assertThat(decodeAll(codec,
                new ToolCallStartEvent("r", "c1", "get_weather"),
                new ToolCallDeltaEvent("r", "c1", "get_weather", "{\"ci"),
                new ToolCallDeltaEvent("r", "c1", "get_weather", "ty\":\"北京\"}")))
                .as("end 之前不输出").isEmpty();

        assertThat(codec.decode(new ToolCallEndEvent("r", "c1", "get_weather")))
                .containsExactly(new ChatEvent.ToolCall("c1", "get_weather", "{\"city\":\"北京\"}"));
    }

    @Test
    void 名字只在start上出现时从缓存回填() {
        List<ChatEvent> events = decodeAll(new EventCodec("s"),
                new ToolCallStartEvent("r", "c1", "get_weather"),
                new ToolCallDeltaEvent("r", "c1", null, "{}"),
                new ToolCallEndEvent("r", "c1", null),
                new ToolResultStartEvent("r2", "c1", null),
                new ToolResultTextDeltaEvent("r2", "c1", null, "晴"),
                new ToolResultEndEvent("r2", "c1", null, ToolResultState.SUCCESS));

        assertThat(events).containsExactly(
                new ChatEvent.ToolCall("c1", "get_weather", "{}"),
                new ChatEvent.ToolResult("c1", "get_weather", "success", "晴"));
    }

    @Test
    void 没有入参碎片时参数为空对象() {
        List<ChatEvent> events = decodeAll(new EventCodec("s"),
                new ToolCallStartEvent("r", "c1", "now"),
                new ToolCallEndEvent("r", "c1", "now"));

        assertThat(events).containsExactly(new ChatEvent.ToolCall("c1", "now", "{}"));
    }

    @Test
    void 并行的多个工具调用按ID各自攒齐() {
        List<ChatEvent> events = decodeAll(new EventCodec("s"),
                new ToolCallStartEvent("r", "a", "f"),
                new ToolCallStartEvent("r", "b", "g"),
                new ToolCallDeltaEvent("r", "a", null, "{\"x\":1}"),
                new ToolCallDeltaEvent("r", "b", null, "{\"y\":2}"),
                new ToolCallEndEvent("r", "a", null),
                new ToolCallEndEvent("r", "b", null));

        assertThat(events).containsExactly(
                new ChatEvent.ToolCall("a", "f", "{\"x\":1}"),
                new ChatEvent.ToolCall("b", "g", "{\"y\":2}"));
    }

    @Test
    void 工具结果的文本碎片攒齐并带上状态() {
        List<ChatEvent> events = decodeAll(new EventCodec("s"),
                new ToolResultStartEvent("r", "c1", "f"),
                new ToolResultTextDeltaEvent("r", "c1", "f", "出"),
                new ToolResultTextDeltaEvent("r", "c1", "f", "错"),
                new ToolResultEndEvent("r", "c1", "f", ToolResultState.ERROR));

        assertThat(events).containsExactly(new ChatEvent.ToolResult("c1", "f", "error", "出错"));
    }

    @Test
    void 用量跨多次模型调用累加并随结束事件输出() {
        List<ChatEvent> events = decodeAll(new EventCodec("s-1"),
                usage(10, 4),
                new ModelCallEndEvent("r", null),
                usage(5, 3),
                new AgentResultEvent(new AssistantMessage("最终回复")),
                new AgentEndEvent("r"));

        assertThat(events).containsExactly(
                new ChatEvent.Done("s-1", "最终回复", new ChatEvent.Usage(15, 7, 22)));
    }

    @Test
    void 没有结果事件时结束事件的文本为空() {
        assertThat(new EventCodec("s").decode(new AgentEndEvent("r")))
                .containsExactly(new ChatEvent.Done("s", "", new ChatEvent.Usage(0, 0, 0)));
    }

    @Test
    void 每个请求新建的实例之间互不影响() {
        EventCodec first = new EventCodec("s-1");
        first.decode(usage(10, 4));
        first.decode(new ToolCallStartEvent("r", "c1", "f"));

        EventCodec second = new EventCodec("s-2");
        List<ChatEvent> events = decodeAll(second,
                new ToolCallEndEvent("r", "c1", null),
                new AgentEndEvent("r"));

        assertThat(events).containsExactly(
                new ChatEvent.ToolCall("c1", "", "{}"),
                new ChatEvent.Done("s-2", "", new ChatEvent.Usage(0, 0, 0)));
    }

    @Test
    void 事件名与事件类型一一对应() {
        assertThat(new ChatEvent.TextDelta("a").eventName()).isEqualTo("textDelta");
        assertThat(new ChatEvent.ToolCall("1", "n", "{}").eventName()).isEqualTo("toolCall");
        assertThat(new ChatEvent.ToolResult("1", "n", "success", "").eventName()).isEqualTo("toolResult");
        assertThat(new ChatEvent.Hint("s", "t").eventName()).isEqualTo("hint");
        assertThat(new ChatEvent.Done("s", "t", new ChatEvent.Usage(0, 0, 0)).eventName()).isEqualTo("done");
        assertThat(new ChatEvent.Error("m").eventName()).isEqualTo("error");
    }
}
