package com.yoke.tool;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yoke 自带的几个通用工具。
 *
 * <p>纯 POJO，不依赖 Spring：启动时只实例化一次，注册进共享的 Toolkit 模板，之后每个请求的 Toolkit 副本
 * 共享同一个实例（{@code Toolkit.copy()} 按引用共享工具对象）。所以<b>不能保存任何请求态字段</b>，
 * 工具方法只依赖入参和不可变的字段，天然线程安全。
 *
 * <p>description 写的是「什么时候该用它」而不是功能罗列：模型靠它决定要不要调用。
 * 参数名用 {@code @ToolParam(name = ...)} 显式声明，不依赖编译参数。
 */
public class ChatTools {

    private static final Logger log = LoggerFactory.getLogger(ChatTools.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx");

    /** 笔记工具单次最多返回的条数。 */
    static final int MAX_NOTES = 20;

    /** 演示用的内存假数据：真实系统里这里是按 userId 查库。 */
    private static final Map<String, List<String>> NOTES = Map.of(
            "default", List.of("周五前把第 03 篇教程初稿写完", "给 Redis 加上密码", "读一遍 HarnessAgent 的子 Agent 文档"),
            "alice", List.of("和产品对齐检索的引用格式", "评估 MCP 服务的鉴权方式"));

    private final Clock clock;

    public ChatTools() {
        this(Clock.systemUTC());
    }

    /** 时钟可注入，测试里用固定时间，不依赖真实的当前时刻。 */
    public ChatTools(Clock clock) {
        this.clock = clock;
    }

    @Tool(name = "get_current_time",
            description = "当用户问现在几点、今天几号、星期几，或者需要知道当前日期才能回答时调用。"
                    + "模型自己不知道当前时间，不要凭印象回答。返回带时区偏移的日期时间。")
    public String getCurrentTime(
            @ToolParam(name = "timezone", required = false,
                    description = "IANA 时区名，例如 Asia/Shanghai、America/New_York；不传时用 UTC") String timezone) {
        ZoneId zone;
        try {
            zone = ZoneId.of(Optional.ofNullable(timezone).filter(StrUtil::isNotBlank).map(String::trim).orElse("UTC"));
        } catch (DateTimeException e) {
            return "无法识别的时区：" + abbreviate(timezone) + "。请使用 IANA 时区名，例如 Asia/Shanghai。";
        }
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.now(clock), zone);
        return now.format(TIME_FORMAT) + "（" + zone.getId() + "，" + now.getDayOfWeek() + "）";
    }

    @Tool(name = "calculate",
            description = "当用户要做加减乘除运算、算价格或比例、核对数字时调用，不要心算大数或小数。"
                    + "只支持数字、+ - * /、括号和小数，不支持函数和变量。表达式不合法时返回错误原因，请据此修正后重试。")
    public String calculate(
            @ToolParam(name = "expression", description = "算术表达式，例如 (1.5 + 2) * 4 / 3") String expression) {
        try {
            return ExpressionEvaluator.evaluate(expression);
        } catch (ExpressionEvaluator.InvalidExpressionException e) {
            return "无法计算：" + e.getMessage();
        }
    }

    /**
     * 演示「业务上下文不经过模型」：{@code user} 没有 {@code @ToolParam}，由框架按类型从 RuntimeContext 注入，
     * 不进工具的 JSON Schema。取不到时（没人放进上下文）拒绝返回，而不是回落到某个默认用户。
     */
    @Tool(name = "list_my_notes",
            description = "当用户问「我的笔记」「我记了什么」「我有哪些待办」时调用。只返回当前用户自己的笔记，无需也不能指定用户。")
    public String listMyNotes(
            UserContext user,
            @ToolParam(name = "limit", required = false, description = "最多返回几条，默认 5，上限 20") Integer limit) {
        if (Objects.isNull(user) || StrUtil.isBlank(user.userId())) {
            log.warn("list_my_notes 被调用但 RuntimeContext 里没有 UserContext，拒绝返回数据");
            return "无法确定当前用户，已拒绝返回笔记。";
        }
        int max = Optional.ofNullable(limit).map(value -> Math.clamp(value, 1, MAX_NOTES)).orElse(5);
        List<String> notes = NOTES.getOrDefault(user.userId(), List.of());
        if (notes.isEmpty()) {
            return "当前用户（" + user.userId() + "）还没有笔记。";
        }
        StringBuilder out = new StringBuilder("当前用户（").append(user.userId()).append("）的笔记：");
        List<String> shown = notes.subList(0, Math.min(max, notes.size()));
        for (int i = 0; i < shown.size(); i++) {
            out.append("\n").append(i + 1).append(". ").append(shown.get(i));
        }
        return out.toString();
    }

    private static String abbreviate(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }
}
