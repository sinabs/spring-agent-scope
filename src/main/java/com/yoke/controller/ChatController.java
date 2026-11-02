package com.yoke.controller;

import java.util.Optional;
import java.util.UUID;

import cn.hutool.core.util.StrUtil;
import com.yoke.api.ChatApi;
import com.yoke.codec.EventCodec;
import com.yoke.codec.MessageCodec;
import com.yoke.model.ChatEvent;
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
    public Flux<ServerSentEvent<ChatEvent>> stream(ChatRequest request) {
        String sessionId = resolveSessionId(request);

        return Flux.using(
                        agentBuilder::build,
                        agent -> {
                            EventCodec codec = new EventCodec(sessionId);
                            return agent.streamEvents(MessageCodec.toInput(request.message()), contextOf(sessionId))
                                    .concatMapIterable(codec::decode);
                        },
                        ReActAgent::close)
                .onErrorResume(error -> {
                    log.warn("流式对话出错，sessionId={}", sessionId, error);
                    return Flux.just(new ChatEvent.Error(describe(error)));
                })
                .map(event -> ServerSentEvent.builder(event).event(event.eventName()).build());
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

    private static String describe(Throwable error) {
        return Optional.ofNullable(error.getMessage())
                .filter(StrUtil::isNotBlank)
                .orElseGet(() -> error.getClass().getSimpleName());
    }
}
