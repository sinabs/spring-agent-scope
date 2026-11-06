package com.yoke.codec;

import java.util.List;

import com.yoke.config.AguiConfiguration;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.ImageInputContent;
import io.agentscope.core.agui.model.InputContent;
import io.agentscope.core.agui.model.InputContentUrlSource;
import io.agentscope.core.agui.model.TextInputContent;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 流式请求体的解析与校验：任何一项不合规都要在建立流之前被拒绝。
 */
class AguiStreamCodecTests {

    private final AguiConfiguration configuration = new AguiConfiguration();

    private final AguiStreamCodec codec = configuration.aguiStreamCodec(configuration.aguiAdapterConfig());

    private static String request(String threadId, String messages) {
        return "{\"threadId\":" + threadId + ",\"runId\":\"r-1\",\"messages\":" + messages + "}";
    }

    private static final String USER_HI = "[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"你好\"}]";

    private void 应当被拒绝(String body) {
        assertThatThrownBy(() -> codec.parse(body))
                .as(body)
                .isInstanceOf(InvalidStreamRequestException.class);
    }

    /** 用库自己的序列化拼一条带内容块的 user 消息，不手写 JSON 格式。 */
    private static String userBlocks(InputContent... parts) {
        return "[" + JsonUtils.getJsonCodec().toJson(AguiMessage.userMessage("m1", List.of(parts))) + "]";
    }

    @Test
    void 内容块形式的content被拒绝并说明原因() {
        // 带图片的、全是文本块的都拒绝：只支持字符串形式的 content
        for (String messages : new String[] {
                userBlocks(new TextInputContent("这张图是什么"),
                        new ImageInputContent(new InputContentUrlSource("https://example.com/a.png"), null)),
                userBlocks(new TextInputContent("你好"), new TextInputContent("在吗"))}) {
            assertThatThrownBy(() -> codec.parse(request("\"t-1\"", messages)))
                    .as(messages)
                    .isInstanceOf(InvalidStreamRequestException.class)
                    .hasMessageContaining("内容块");
        }
    }

    @Test
    void 合法请求解析出threadId和runId() {
        AguiStreamCodec.StreamRequest parsed = codec.parse(request("\"t-1\"", USER_HI));

        assertThat(parsed.threadId()).isEqualTo("t-1");
        assertThat(parsed.runId()).isEqualTo("r-1");
    }

    @Test
    void runId缺失或为空白时被拒绝() {
        for (String runIdField : new String[] {"", "\"runId\":null,", "\"runId\":\"\",", "\"runId\":\"  \","}) {
            应当被拒绝("{\"threadId\":\"t-1\"," + runIdField + "\"messages\":" + USER_HI + "}");
        }
    }

    @Test
    void 非法JSON或空请求体被拒绝() {
        for (String body : new String[] {null, "", "  ", "{", "not json", "[]", "\"x\"", "null", "123"}) {
            应当被拒绝(body);
        }
    }

    @Test
    void messages缺失或为空被拒绝() {
        应当被拒绝("{\"threadId\":\"t-1\",\"runId\":\"r-1\"}");
        应当被拒绝(request("\"t-1\"", "[]"));
        应当被拒绝(request("\"t-1\"", "null"));
    }

    @Test
    void 最后一条不是user消息被拒绝() {
        应当被拒绝(request("\"t-1\"", "[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"你好\"},"
                + "{\"id\":\"m2\",\"role\":\"assistant\",\"content\":\"你好呀\"}]"));
        应当被拒绝(request("\"t-1\"", "[{\"id\":\"m1\",\"role\":\"system\",\"content\":\"规则\"}]"));
        应当被拒绝(request("\"t-1\"", "[{\"id\":\"m1\",\"role\":\"tool\",\"content\":\"x\",\"toolCallId\":\"c\"}]"));
    }

    @Test
    void messages里有null元素被拒绝而不是空指针() {
        应当被拒绝(request("\"t-1\"", "[null]"));
        应当被拒绝(request("\"t-1\"", "[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"你好\"},null]"));
    }

    @Test
    void 适配器只用服务端注册的工具() {
        assertThat(configuration.aguiAdapterConfig().getToolMergeMode()).isEqualTo(ToolMergeMode.AGENT_ONLY);
    }

    @Test
    void 最后一条user消息没有文本或只有空白被拒绝() {
        for (String content : new String[] {"\"\"", "\"   \"", "null", "[]"}) {
            应当被拒绝(request("\"t-1\"", "[{\"id\":\"m1\",\"role\":\"user\",\"content\":" + content + "}]"));
        }
        应当被拒绝(request("\"t-1\"", "[{\"id\":\"m1\",\"role\":\"user\"}]"));
    }

    @Test
    void 消息缺少id或role被拒绝() {
        应当被拒绝(request("\"t-1\"", "[{\"role\":\"user\",\"content\":\"你好\"}]"));
        应当被拒绝(request("\"t-1\"", "[{\"id\":\"m1\",\"content\":\"你好\"}]"));
    }

    @Test
    void 非法threadId被拒绝() {
        List<String> illegal = List.of("null", "\"\"", "\"  \"", "\"a b\"", "\"a/b\"", "\"../x\"", "\"..\"", "\".\"",
                "\"...\"", "\"中文\"", "\"" + "a".repeat(65) + "\"", "{}", "\"a\\nb\"");
        for (String threadId : illegal) {
            应当被拒绝(request(threadId, USER_HI));
        }
        应当被拒绝("{\"runId\":\"r-1\",\"messages\":" + USER_HI + "}");
    }

    @Test
    void 合法threadId的边界值通过() {
        for (String threadId : new String[] {"a", "A_b-1.2", ".a", "a.", "..a", "a".repeat(64), "0"}) {
            assertThat(codec.parse(request("\"" + threadId + "\"", USER_HI)).threadId()).isEqualTo(threadId);
        }
    }
}
