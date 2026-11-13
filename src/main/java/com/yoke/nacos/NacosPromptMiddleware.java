package com.yoke.nacos;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import cn.hutool.core.util.StrUtil;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


/**
 * Prompt 热更新：每轮组装系统提示词时，直接向 Nacos 拉最新的 Prompt，追加到内置提示词之后。
 *
 * <p>为什么每轮直接拉，而不用官方的 {@code NacosPromptListener}：后者在本地缓存 Prompt，靠服务端推送更新，
 * 一次性手工测量（AgentScope 加 Nacos 3.2.1）改完后要过 8 到 18 秒缓存才变，而直接 {@code getPrompt} 改完立刻拿到新版本。
 * 代价是每轮多一次 gRPC 往返。
 *
 * <p>为什么是追加而不是替换：内置提示词里还有 Harness 自己拼进去的工作区、工具、技能说明，替换会把它们一起抹掉。
 *
 * <p><b>信任边界：谁能写 Nacos 里这个 Prompt，谁就能改 Yoke 的系统提示词</b>（它在内置提示词之后，对模型有很高的权重）。
 * 所以 Prompt 的来源必须受信，生产必须开启 Nacos 鉴权，并限制谁能发布这个 Key。
 *
 * <p>保证：
 * <ul>
 *   <li><b>拉失败原样返回</b>：Nacos 连不上、超时（{@code yoke.nacos.prompt.timeout}，默认 2 秒）、Prompt 不存在、内容空白，
 *       都返回当前提示词，对话不受影响；</li>
 *   <li><b>两种情形分开记日志并限频</b>：「Prompt 不存在」（配置问题）和「Nacos 不可达或超时」（环境问题）各自每分钟最多一条 WARN，
 *       其余降到 debug，避免每轮都刷屏；</li>
 *   <li><b>防重复拼接</b>：当前提示词已包含这段内容就不再追加；</li>
 *   <li><b>不卡住事件循环</b>：取值是阻塞调用，放到弹性线程池上执行。注意 {@code Mono.timeout} 只取消<b>等待</b>，
 *       阻塞在弹性线程上的 {@code getPrompt} 仍占着线程直到 gRPC 自己超时；Nacos 持续不可达时，每一轮对话会占住一个弹性线程
 *       （线程池有界，所以是有界的资源占用，不是无限增长）。</li>
 * </ul>
 */
@Slf4j
public class NacosPromptMiddleware implements MiddlewareBase {

    private static final long WARN_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final AiService aiService;
    private final String key;
    private final Duration timeout;
    private final AtomicLong lastMissingWarn = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS - 1);
    private final AtomicLong lastUnavailableWarn = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS - 1);

    public NacosPromptMiddleware(AiService aiService, String key, Duration timeout) {
        this.aiService = aiService;
        this.key = key;
        this.timeout = timeout;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.fromCallable(() -> aiService.getPrompt(key))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(timeout)
                // getTemplate 可能是 null：当作「没有内容」，走和 Prompt 不存在相同的分支
                .flatMap(prompt -> Mono.justOrEmpty(prompt.getTemplate()))
                .map(latest -> merge(currentPrompt, latest))
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    warnLimited(lastMissingWarn, "Nacos 里的 Prompt 没有内容，沿用内置提示词（key={}）", key);
                    return currentPrompt;
                }))
                .onErrorResume(error -> {
                    if (error instanceof NacosException nacos && nacos.getErrCode() == NacosException.NOT_FOUND) {
                        warnLimited(lastMissingWarn, "Nacos 里没有这个 Prompt，沿用内置提示词（key={}）", key);
                    } else {
                        warnLimited(lastUnavailableWarn, "从 Nacos 拉取 Prompt 失败或超时，沿用内置提示词（key={}，原因：{}）",
                                key, error.getClass().getSimpleName());
                    }
                    return Mono.just(currentPrompt);
                });
    }

    private void warnLimited(AtomicLong last, String message, Object... args) {
        long now = System.nanoTime();
        long previous = last.get();
        if (now - previous >= WARN_INTERVAL_NANOS && last.compareAndSet(previous, now)) {
            log.warn(message, args);
        } else {
            log.debug(message, args);
        }
    }

    /** 把最新的提示词追加到当前提示词之后；空白或已包含时原样返回。 */
    static String merge(String current, String latest) {
        if (StrUtil.isBlank(latest)) {
            return current;
        }
        String addition = latest.strip();
        if (Objects.nonNull(current) && current.contains(addition)) {
            return current;
        }
        String base = Optional.ofNullable(current).filter(StrUtil::isNotBlank).map(text -> text.stripTrailing() + "\n\n").orElse("");
        return base + addition;
    }
}
