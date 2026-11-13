package com.yoke.a2a.runner;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import cn.hutool.core.util.StrUtil;
import com.yoke.codec.MessageCodec;
import com.yoke.config.HarnessAgentFactory;
import com.yoke.model.SafeId;
import com.yoke.model.UserContext;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;


/**
 * 把「每请求新建 HarnessAgent」接成 A2A 服务端的 {@link AgentRunner}。
 *
 * <p>官方自带的 runner 只认 ReActAgent，而 Yoke 的 Agent 是 HarnessAgent，所以这里自己实现：
 * 每次调用走 {@link HarnessAgentFactory#create()} 新建 Agent，流结束、出错、被取消时都关闭它，
 * 工具、子 Agent、权限收紧和 HTTP 接口完全一致，A2A 入口不会多出任何工具。
 *
 * <p>三处刻意收紧：
 * <ul>
 *   <li>入站消息只取其文本内容，合成一条<b>用户消息</b>。A2A 的 part 元数据可以自报角色，
 *       也可以带工具调用块，原样转交会让远端伪造系统消息或工具结果；（框架会把 data 类型的 part 转成文本，实测，所以它仍以用户输入的形态出现）</li>
 *   <li>userId / sessionId 再校验一次 {@link SafeId} 规则（网关已经校验过，这里是第二道），缺失时与 HTTP 接口同样处理；</li>
 *   <li>只放行最终结果事件，并只保留文本（不带思考过程等），与 {@code /api/chat} 返回的内容一致。</li>
 * </ul>
 *
 * <p>错误不外泄也不吞：框架会把流里的异常原文拼进回复文本，而回复文本在 A2A 里看起来像一次成功的答复。
 * 所以这里把失败记在 {@link #takeFailure} 的台账里（按 taskId），网关据此改写成 JSON-RPC 的错误响应，
 * 异常堆栈和内部信息只进服务端日志。
 *
 * <p>{@link #stop} 是空实现：服务端只开放同步的 {@code message/send}，调用在返回前不会被单独取消，
 * 也没有暴露 {@code tasks/cancel}，所以没有「执行中、可被停止」的任务。
 */
@Slf4j
public class YokeAgentRunner implements AgentRunner {

    /** 与 HTTP 接口一致：缺 userId 时的默认用户。 */
    static final String DEFAULT_USER_ID = "default";

    /** 台账最多记这么多条失败，防止网关没来取时无限增长。 */
    private static final int MAX_FAILURES = 1024;

    private final HarnessAgentFactory agentFactory;
    private final String name;
    private final String description;
    private final Duration timeout;

    private final Map<String, String> failures = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_FAILURES;
        }
    };

    public YokeAgentRunner(HarnessAgentFactory agentFactory, String name, String description, Duration timeout) {
        this.agentFactory = agentFactory;
        this.name = name;
        this.description = description;
        this.timeout = timeout;
    }

    @Override
    public String getAgentName() {
        return name;
    }

    @Override
    public String getAgentDescription() {
        return description;
    }

    @Override
    public Flux<AgentEvent> streamEvents(List<Msg> requestMessages, AgentRequestOptions options) {
        String taskId = options.getTaskId();
        String userId;
        String sessionId;
        String text;
        try {
            userId = resolveId(options.getUserId(), DEFAULT_USER_ID, "userId");
            sessionId = resolveId(options.getSessionId(), UUID.randomUUID().toString(), "sessionId");
            text = textOf(requestMessages);
        } catch (IllegalArgumentException e) {
            recordFailure(taskId, e.getMessage());
            throw e;
        }
        return Flux.using(
                        agentFactory::create,
                        agent -> agent.streamEvents(MessageCodec.toInput(text), contextOf(userId, sessionId))
                                .ofType(AgentResultEvent.class)
                                .map(YokeAgentRunner::textOnly),
                        HarnessAgent::close)
                .timeout(timeout)
                .doOnError(error -> {
                    // 堆栈只进服务端日志；对外只留一句不含内部信息的说明
                    log.warn("A2A 调用出错，taskId={} sessionId={}", taskId, sessionId, error);
                    recordFailure(taskId, error instanceof TimeoutException ? "智能体执行超时" : "智能体执行失败");
                });
    }

    @Override
    public void stop(String taskId) {
        // 见类说明：同步调用没有可停止的在途任务
    }

    /** 取走（并清除）某个任务的失败说明；没有失败返回空。 */
    public Optional<String> takeFailure(String taskId) {
        if (Objects.isNull(taskId)) {
            return Optional.empty();
        }
        synchronized (failures) {
            return Optional.ofNullable(failures.remove(taskId));
        }
    }

    private void recordFailure(String taskId, String reason) {
        if (Objects.nonNull(taskId)) {
            synchronized (failures) {
                failures.put(taskId, reason);
            }
        }
    }

    private static String resolveId(String value, String fallback, String field) {
        if (StrUtil.isBlank(value)) {
            return fallback;
        }
        if (!SafeId.Validator.isSafe(value)) {
            throw new IllegalArgumentException(field + " 格式不合法：只能包含字母、数字、下划线、点和短横线，长度 1 到 64，且不能全是点");
        }
        return value;
    }

    private static String textOf(List<Msg> messages) {
        String text = messages.stream()
                .map(Msg::getTextContent)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n"));
        if (StrUtil.isBlank(text)) {
            throw new IllegalArgumentException("消息里没有文本内容");
        }
        return text;
    }

    private static AgentEvent textOnly(AgentResultEvent event) {
        Optional<Msg> result = Optional.ofNullable(event.getResult());
        String text = result.map(MessageCodec::toText).orElse("");
        return new AgentResultEvent(Msg.builder().id(result.map(Msg::getId).orElse(null))
                .name(result.map(Msg::getName).orElse(null))
                .role(MsgRole.ASSISTANT)
                .textContent(text)
                .build());
    }

    private static RuntimeContext contextOf(String userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                // 与 HTTP 接口相同：业务工具按类型取当前用户，模型看不到也伪造不了
                .put(UserContext.class, new UserContext(userId))
                .build();
    }
}
