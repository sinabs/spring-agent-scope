package com.yoke.support;

import java.util.List;
import java.util.Map;

import io.agentscope.core.util.JsonUtils;

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
}
