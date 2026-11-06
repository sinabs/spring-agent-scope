package com.yoke.middleware;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import org.springframework.stereotype.Component;

/**
 * 记录一次对话的耗时轨迹：整次调用、每次模型调用、每批工具调用各一条日志。
 *
 * <p>只记录 agentId、userId、sessionId、耗时和工具名，不记录消息内容，也不记录工具的参数和输出，
 * 因为这些里面可能有用户隐私或密钥。出错只记 warn 和异常类型，异常本身继续向上抛，不吞掉。
 *
 * <p>在 AgentScope 中 {@link MiddlewareBase} 是接口，钩子都有默认实现，这里只覆盖三个。
 * 钩子返回的是事件流，所以计时必须放进 {@code Flux.defer}：订阅时才开始计，而不是构造流的时候。
 */
@Slf4j
@Component
public class LoggingMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        return traced("agent", agent, ctx, "", () -> next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, RuntimeContext ctx, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        String model = Optional.ofNullable(input.model()).map(Model::getModelName).orElse("unknown");
        return traced("model", agent, ctx, "model=" + model, () -> next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        List<String> tools = input.toolCalls().stream().map(ToolUseBlock::getName).toList();
        return traced("acting", agent, ctx, "tools=" + tools, () -> next.apply(input));
    }

    private static Flux<AgentEvent> traced(
            String phase, Agent agent, RuntimeContext ctx, String detail, Supplier<Flux<AgentEvent>> call) {
        return Flux.defer(() -> {
            long start = System.nanoTime();
            String who = "agent=" + agent.getName() + " agentId=" + agent.getAgentId() + " userId=" + ctx.getUserId() + " sessionId=" + ctx.getSessionId();
            return call.get()
                    .doOnError(error -> log.warn("{} 出错 {} {} 耗时={}ms 异常={}",
                            phase, who, detail, elapsedMillis(start), error.getClass().getSimpleName()))
                    // 用 doFinally 而不是 doOnComplete + doOnCancel：下游拿到结果后常常会再 cancel 一次，
                    // doOnCancel 会在完成之后又打一条「被取消」；doFinally 保证每次订阅只收尾一次
                    .doFinally(signal -> {
                        if (signal != SignalType.ON_ERROR) {
                            log.debug("{} {} {} {} 耗时={}ms", phase,
                                    signal == SignalType.CANCEL ? "被取消" : "完成", who, detail, elapsedMillis(start));
                        }
                    });
        });
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
