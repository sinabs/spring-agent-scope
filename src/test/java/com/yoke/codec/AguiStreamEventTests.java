package com.yoke.codec;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.yoke.config.AguiConfiguration;
import com.yoke.support.AguiTestSupport;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static com.yoke.support.AguiTestSupport.read;
import static com.yoke.support.AguiTestSupport.types;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实的 ReActAgent 加脚本化的假模型，验证适配器输出的 AG-UI 事件序列和字段。
 * 不访问任何网络。
 */
class AguiStreamEventTests {

    private final AguiConfiguration configuration = new AguiConfiguration();

    private final AguiStreamCodec codec = configuration.aguiStreamCodec(configuration.aguiAdapterConfig());

    /** 按调用次数依次返回预设响应；预设用完后抛错。 */
    static class ScriptedModel implements Model {

        private final List<Flux<ChatResponse>> script;

        private final AtomicInteger calls = new AtomicInteger();

        ScriptedModel(List<Flux<ChatResponse>> script) {
            this.script = script;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int index = calls.getAndIncrement();
            return index < script.size() ? script.get(index) : Flux.error(new IllegalStateException("脚本用完了"));
        }

        @Override
        public String getModelName() {
            return "scripted";
        }
    }

    /** 测试用的本地工具，不联网。 */
    public static class CityTool {

        @Tool(name = "city_info", description = "查询城市信息")
        public String cityInfo(@ToolParam(name = "city", description = "城市名") String city) {
            return city + "：晴，25 度";
        }
    }

    private static ChatResponse text(String text, ChatUsage usage) {
        return ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).usage(usage).build();
    }

    private static ChatResponse toolUseChunk(String fragment) {
        return ChatResponse.builder()
                .content(List.of(new ToolUseBlock("call_1", "city_info", Map.of(), fragment, null)))
                .build();
    }

    private List<String> run(Model model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new CityTool());
        ReActAgent agent = ReActAgent.builder()
                .name("Yoke")
                .model(model)
                .toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore())
                .build();
        AguiStreamCodec.StreamRequest request = codec.parse(AguiTestSupport.body("t-events", "你好"));
        RuntimeContext context = RuntimeContext.builder().userId("default").sessionId("t-events").build();
        List<String> events = codec.stream(agent, request, context).collectList().block();
        agent.close();
        return events;
    }

    @Test
    void 纯文本回复的事件序列() {
        List<String> events = run(new ScriptedModel(List.of(Flux.just(
                text("你", null), text("好", null), text("呀", ChatUsage.builder().inputTokens(10).outputTokens(3).build())))));

        List<String> types = types(events);
        assertThat(types.getFirst()).isEqualTo("RUN_STARTED");
        assertThat(types.getLast()).isEqualTo("RUN_FINISHED");
        assertThat(types).contains("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END");
        assertThat(types.indexOf("TEXT_MESSAGE_START")).isLessThan(types.indexOf("TEXT_MESSAGE_CONTENT"));
        assertThat(types.lastIndexOf("TEXT_MESSAGE_CONTENT")).isLessThan(types.indexOf("TEXT_MESSAGE_END"));
        assertThat(types.indexOf("TEXT_MESSAGE_END")).isLessThan(types.indexOf("RUN_FINISHED"));
        assertThat(types).doesNotContain("RUN_ERROR");
        // 每个事件都带同一组 threadId 和 runId，RUN_STARTED 是第一个
        assertThat(events).allSatisfy(json -> assertThat(read(json)).containsEntry("threadId", "t-events"));
        assertThat(read(events.getFirst())).containsEntry("runId", "run-test");
        // 客户端拼接 delta 得到完整回复
        String reply = events.stream().map(AguiTestSupport::read)
                .filter(e -> "TEXT_MESSAGE_CONTENT".equals(e.get("type")))
                .map(e -> (String) e.get("delta"))
                .reduce("", String::concat);
        assertThat(reply).isEqualTo("你好呀");
    }

    @Test
    void 编码结果没有前导空格且是单行() {
        List<String> events = run(new ScriptedModel(List.of(Flux.just(text("ok", null)))));

        assertThat(events).allSatisfy(json ->
                assertThat(json).startsWith("{").doesNotContain("\n"));
    }

    @Test
    void 工具调用的事件序列() {
        // 真实模型流式返回工具调用时，入参 JSON 是分片到达的
        ChatResponse first = toolUseChunk("{\"city\":\"北");
        ChatResponse second = toolUseChunk("京\"}");
        List<String> events = run(new ScriptedModel(List.of(Flux.just(first, second), Flux.just(text("北京晴", null)))));

        List<String> types = types(events);
        assertThat(types).contains("TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_END", "TOOL_CALL_RESULT");
        assertThat(types.indexOf("TOOL_CALL_START")).isLessThan(types.indexOf("TOOL_CALL_ARGS"));
        assertThat(types.indexOf("TOOL_CALL_ARGS")).isLessThan(types.indexOf("TOOL_CALL_END"));
        assertThat(types.indexOf("TOOL_CALL_END")).isLessThan(types.indexOf("TOOL_CALL_RESULT"));
        assertThat(types.getLast()).isEqualTo("RUN_FINISHED");
        Map<String, Object> result = events.stream().map(AguiTestSupport::read)
                .filter(e -> "TOOL_CALL_RESULT".equals(e.get("type"))).findFirst().orElseThrow();
        assertThat(result).containsEntry("toolCallId", "call_1");
        assertThat(String.valueOf(result.get("content"))).contains("北京：晴，25 度");
    }

    @Test
    void 用量以CUSTOM_token_usage事件输出() {
        List<String> events = run(new ScriptedModel(List.of(Flux.just(
                text("好", ChatUsage.builder().inputTokens(10).outputTokens(3).build())))));

        List<Map<String, Object>> usage = events.stream().map(AguiTestSupport::read)
                .filter(e -> "CUSTOM".equals(e.get("type")) && "token_usage".equals(e.get("name"))).toList();
        assertThat(usage).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> cumulative = (Map<String, Object>) ((Map<String, Object>) usage.getFirst().get("value")).get("cumulative");
        assertThat(cumulative).containsEntry("inputTokens", 10).containsEntry("outputTokens", 3).containsEntry("totalTokens", 13);
    }

    @Test
    void 模型出错时以RUN_ERROR收尾且没有RUN_FINISHED() {
        List<String> events = run(new ScriptedModel(List.of(Flux.error(new IllegalStateException("模型服务不可用")))));

        List<String> types = types(events);
        assertThat(types.getLast()).isEqualTo("RUN_ERROR");
        assertThat(types).doesNotContain("RUN_FINISHED");
        assertThat(read(events.getLast())).containsEntry("message", "模型服务不可用");
    }
}
