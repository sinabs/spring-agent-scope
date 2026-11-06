package com.yoke.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.yoke.config.AguiConfiguration;
import com.yoke.config.HarnessAgentFactory;
import com.yoke.controller.ChatController;
import io.agentscope.core.util.JsonUtils;

import org.springframework.http.codec.ServerSentEvent;

/**
 * AG-UI 流式接口测试的公共工具：拼请求体、读事件。
 */
public final class AguiTestSupport {

    private AguiTestSupport() {
    }

    /** 只有一条 user 消息的合法请求体。 */
    public static String body(String threadId, String text) {
        return JsonUtils.getJsonCodec().toJson(Map.of(
                "threadId", threadId,
                "runId", "run-test",
                "messages", List.of(Map.of("id", "m-1", "role", "user", "content", text))));
    }

    /** 事件 JSON 解析成 Map，便于断言字段。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(String eventJson) {
        return JsonUtils.getJsonCodec().fromJson(eventJson, Map.class);
    }

    public static String type(String eventJson) {
        return String.valueOf(read(eventJson).get("type"));
    }

    public static List<String> types(List<String> eventJsons) {
        return eventJsons.stream().map(AguiTestSupport::type).toList();
    }

    /** 和线上一样装配的控制器，只把 Agent 工厂换成测试用的。 */
    public static ChatController controller(HarnessAgentFactory factory) {
        AguiConfiguration configuration = new AguiConfiguration();
        return new ChatController(factory, configuration.aguiStreamCodec(configuration.aguiAdapterConfig()));
    }

    /** 走一遍流式接口，返回解析后的事件。流式接口固定归属 default 用户。 */
    public static List<Map<String, Object>> run(ChatController controller, String threadId, String text) {
        return controller.stream(body(threadId, text)).map(ServerSentEvent::data)
                .map(AguiTestSupport::read).collectList().block(Duration.ofSeconds(60));
    }

    /** 某类型的第一个事件，没有就抛异常。 */
    public static Map<String, Object> first(List<Map<String, Object>> events, String type) {
        return events.stream().filter(e -> type.equals(e.get("type"))).findFirst().orElseThrow();
    }

    /** 第一次工具调用的参数：把 TOOL_CALL_ARGS 的 delta 拼起来。 */
    public static String toolArguments(List<Map<String, Object>> events) {
        return events.stream().filter(e -> "TOOL_CALL_ARGS".equals(e.get("type")))
                .map(e -> String.valueOf(e.get("delta"))).reduce("", String::concat);
    }

    /** 回复全文：没有 done 事件，由客户端把 TEXT_MESSAGE_CONTENT 的 delta 拼起来。 */
    public static String text(List<Map<String, Object>> events) {
        return events.stream().filter(e -> "TEXT_MESSAGE_CONTENT".equals(e.get("type")))
                .map(e -> String.valueOf(e.get("delta"))).reduce("", String::concat);
    }
}
