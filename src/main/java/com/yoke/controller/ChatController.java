package com.yoke.controller;

import java.util.Optional;
import java.util.UUID;

import com.yoke.api.ChatApi;
import com.yoke.codec.EventCodec;
import com.yoke.codec.MessageCodec;
import com.yoke.config.HarnessAgentFactory;
import com.yoke.model.ChatEvent;
import com.yoke.model.ChatRequest;
import com.yoke.model.ChatResponse;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController implements ChatApi {

    private static final String DEFAULT_USER_ID = "default";

    private final HarnessAgentFactory agentFactory;

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        String userId = resolveUserId(request);
        String sessionId = resolveSessionId(request);

        return Mono.using(
                agentFactory::create,
                agent -> agent.call(MessageCodec.toInput(request.message()), contextOf(userId, sessionId))
                        .map(reply -> new ChatResponse(sessionId, MessageCodec.toText(reply))),
                HarnessAgent::close);
    }

    @Override
    public Flux<ServerSentEvent<ChatEvent>> stream(ChatRequest request) {
        String userId = resolveUserId(request);
        String sessionId = resolveSessionId(request);

        return Flux.using(
                        agentFactory::create,
                        agent -> {
                            EventCodec codec = new EventCodec(sessionId);
                            return agent.streamEvents(MessageCodec.toInput(request.message()), contextOf(userId, sessionId))
                                    .concatMapIterable(codec::decode);
                        },
                        HarnessAgent::close)
                .onErrorResume(error -> {
                    log.warn("流式对话出错，sessionId={}", sessionId, error);
                    return Flux.just(new ChatEvent.Error(describe(error)));
                })
                .map(event -> ServerSentEvent.builder(event).event(event.eventName()).build());
    }

    private static String resolveSessionId(ChatRequest request) {
        return Optional.ofNullable(request.sessionId())
                .filter(StringUtils::hasText)
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    private static String resolveUserId(ChatRequest request) {
        return Optional.ofNullable(request.userId())
                .filter(StringUtils::hasText)
                .orElse(DEFAULT_USER_ID);
    }

    private static RuntimeContext contextOf(String userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
    }

    private static String describe(Throwable error) {
        return StringUtils.hasText(error.getMessage()) ? error.getMessage() : error.getClass().getSimpleName();
    }
}
