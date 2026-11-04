package com.yoke.controller;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.hutool.core.util.StrUtil;
import com.yoke.api.ChatApi;
import com.yoke.codec.AguiStreamCodec;
import com.yoke.codec.MessageCodec;
import com.yoke.model.ChatRequest;
import com.yoke.model.ChatResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController implements ChatApi {

    private static final String DEFAULT_USER_ID = "default";

    private final ReActAgent.Builder agentBuilder;

    private final AguiStreamCodec aguiCodec;

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        String sessionId = resolveSessionId(request);

        return Mono.using(
                agentBuilder::build,
                agent -> agent.call(MessageCodec.toInput(request.message()), contextOf(sessionId))
                        .map(reply -> new ChatResponse(sessionId, MessageCodec.toText(reply))),
                ReActAgent::close);
    }

    @Override
    public Flux<ServerSentEvent<String>> stream(String body) {
        // 校验失败在这里抛出，发生在建立流之前，所以是 400 而不是流内错误
        AguiStreamCodec.StreamRequest request = aguiCodec.parse(body);

        return Flux.defer(() -> {
                    // 记录是否已经发出过事件：没发出过（例如创建 Agent 失败）就要先补 RUN_STARTED。
                    // 放在 defer 里，每次订阅一份，重复订阅时不会沿用上一次的状态
                    AtomicBoolean started = new AtomicBoolean();
                    return Flux.using(
                                    agentBuilder::build,
                                    agent -> aguiCodec.stream(agent, request, contextOf(request.threadId())),
                                    ReActAgent::close)
                            .doOnNext(data -> started.set(true))
                            .onErrorResume(error -> {
                                log.warn("流式对话出错，threadId={}", request.threadId(), error);
                                return aguiCodec.encodeError(request, error, started.get());
                            });
                })
                .map(data -> ServerSentEvent.builder(data).build());
    }

    private static String resolveSessionId(ChatRequest request) {
        return Optional.ofNullable(request.sessionId())
                .filter(StrUtil::isNotBlank)
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    private static RuntimeContext contextOf(String sessionId) {
        return RuntimeContext.builder()
                .userId(DEFAULT_USER_ID)
                .sessionId(sessionId)
                .build();
    }
}
