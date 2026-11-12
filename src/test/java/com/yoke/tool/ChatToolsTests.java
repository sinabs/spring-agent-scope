package com.yoke.tool;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoke.model.UserContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import io.agentscope.core.message.ToolUseBlock;

import static org.assertj.core.api.Assertions.assertThat;

class ChatToolsTests {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-01T07:30:00Z"), ZoneOffset.UTC);

    private final ChatTools tools = new ChatTools(FIXED);

    // ---- get_current_time ----

    @Test
    void 当前时间带时区偏移和时区名() {
        // 美东 2026-11-01 凌晨 2 点结束夏令时：07:30 UTC 时已经是标准时间 -05:00（不是夏令时的 -04:00）
        assertThat(tools.getCurrentTime("Asia/Shanghai")).startsWith("2026-11-01 15:30:00 +08:00（Asia/Shanghai，SUNDAY）");
        assertThat(tools.getCurrentTime("America/New_York")).startsWith("2026-11-01 02:30:00 -05:00");
    }

    @Test
    void 不传时区默认UTC() {
        assertThat(tools.getCurrentTime(null)).startsWith("2026-11-01 07:30:00 +00:00（UTC");
        assertThat(tools.getCurrentTime("  ")).startsWith("2026-11-01 07:30:00 +00:00");
    }

    @Test
    void 非法时区返回提示而不是抛异常() {
        assertThat(tools.getCurrentTime("Mars/Olympus")).contains("无法识别的时区").contains("Mars/Olympus");
        // 过长的输入在回显时被截断，不会原样灌进上下文
        assertThat(tools.getCurrentTime("x".repeat(500)).length()).isLessThan(200);
    }

    // ---- calculate ----

    @Test
    void 计算返回结果() {
        assertThat(tools.calculate("(1.5 + 2) * 4 / 7")).isEqualTo("2");
    }

    @Test
    void 非法表达式返回清晰的错误文本而不是抛异常() {
        assertThat(tools.calculate("1 / 0")).isEqualTo("无法计算：除数不能为 0");
        assertThat(tools.calculate("System.exit(0)")).startsWith("无法计算：");
        assertThat(tools.calculate(null)).isEqualTo("无法计算：表达式不能为空");
    }

    // ---- list_my_notes ----

    @Test
    void 笔记只返回当前用户自己的() {
        String mine = tools.listMyNotes(new UserContext("alice"), null);

        assertThat(mine).contains("alice").contains("MCP");
        assertThat(mine).doesNotContain("Redis");
        assertThat(tools.listMyNotes(new UserContext("default"), null)).contains("Redis").doesNotContain("MCP");
    }

    @Test
    void 没有上下文时拒绝而不是回落到默认用户() {
        assertThat(tools.listMyNotes(null, 5)).contains("无法确定当前用户").doesNotContain("Redis");
        assertThat(tools.listMyNotes(new UserContext(" "), 5)).contains("无法确定当前用户");
    }

    @Test
    void 笔记条数被钳制在合法范围内() {
        // 默认只给 5 条，演示数据只有 3 条，全部返回
        assertThat(tools.listMyNotes(new UserContext("default"), null).lines().count()).isEqualTo(1 + 3);
        // limit 为 1 只返回一条；0 和负数按 1 处理；超大值不会越界
        assertThat(tools.listMyNotes(new UserContext("default"), 1).lines().count()).isEqualTo(1 + 1);
        assertThat(tools.listMyNotes(new UserContext("default"), 0).lines().count()).isEqualTo(1 + 1);
        assertThat(tools.listMyNotes(new UserContext("default"), -9).lines().count()).isEqualTo(1 + 1);
        assertThat(tools.listMyNotes(new UserContext("default"), Integer.MAX_VALUE).lines().count()).isEqualTo(1 + 3);
    }

    @Test
    void 没有笔记的用户得到明确提示() {
        assertThat(tools.listMyNotes(new UserContext("nobody"), null)).contains("还没有笔记");
    }

    // ---- 经过真实 Toolkit 的注入 ----

    private static Toolkit toolkit(ChatTools tools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        return toolkit;
    }

    private static String call(Toolkit toolkit, String name, Map<String, Object> input, RuntimeContext context) {
        // Toolkit 校验入参时读的是 content 里的原始 JSON，所以和 input 一起给
        ToolUseBlock use = ToolUseBlock.builder().id("c1").name(name).input(input).content(json(input)).build();
        ToolResultBlock result = toolkit.callTool(ToolCallParam.builder()
                .toolUseBlock(use).input(input).runtimeContext(context).build()).block();
        return result.getOutput().stream().filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText()).reduce("", String::concat);
    }

    private static String json(Map<String, Object> input) {
        try {
            return new ObjectMapper().writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void 模型看到的工具清单里没有UserContext参数() {
        List<ToolSchema> schemas = toolkit(tools).getToolSchemas();

        ToolSchema notes = schemas.stream().filter(s -> s.getName().equals("list_my_notes")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) notes.getParameters().get("properties");
        // 只有模型该填的 limit；userId、UserContext 都不在 Schema 里
        assertThat(properties.keySet()).containsExactly("limit");
        assertThat(notes.getParameters().toString()).doesNotContainIgnoringCase("userId").doesNotContain("UserContext");
    }

    @Test
    void 工具里拿到的是RuntimeContext里的用户而不是模型传的() {
        Toolkit toolkit = toolkit(tools);
        RuntimeContext alice = RuntimeContext.builder().userId("alice").put(UserContext.class, new UserContext("alice")).build();

        // 模型即便硬塞一个 userId 参数，也不会生效
        String result = call(toolkit, "list_my_notes", Map.of("limit", 5, "userId", "default", "user", Map.of("userId", "default")), alice);

        assertThat(result).contains("alice").contains("MCP").doesNotContain("Redis");
    }

    @Test
    void 换一个用户拿到不同的笔记() {
        Toolkit toolkit = toolkit(tools);
        RuntimeContext alice = RuntimeContext.builder().put(UserContext.class, new UserContext("alice")).build();
        RuntimeContext other = RuntimeContext.builder().put(UserContext.class, new UserContext("default")).build();

        assertThat(call(toolkit, "list_my_notes", Map.of(), alice)).contains("MCP");
        assertThat(call(toolkit, "list_my_notes", Map.of(), other)).contains("Redis");
    }

    @Test
    void 上下文里没有放UserContext时工具拒绝服务() {
        String result = call(toolkit(tools), "list_my_notes", Map.of(), RuntimeContext.builder().userId("alice").build());

        assertThat(result).contains("无法确定当前用户");
    }

    @Test
    void 并发调用互不串用户() throws Exception {
        Toolkit toolkit = toolkit(tools);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Boolean>> jobs = new java.util.ArrayList<>();
            for (int i = 0; i < 200; i++) {
                boolean alice = i % 2 == 0;
                jobs.add(() -> {
                    RuntimeContext context = RuntimeContext.builder()
                            .put(UserContext.class, new UserContext(alice ? "alice" : "default")).build();
                    String out = call(toolkit, "list_my_notes", Map.of(), context);
                    return alice ? out.contains("MCP") && !out.contains("Redis") : out.contains("Redis") && !out.contains("MCP");
                });
            }
            for (var future : executor.invokeAll(jobs, 30, TimeUnit.SECONDS)) {
                assertThat(future.get()).isTrue();
            }
        }
    }
}
