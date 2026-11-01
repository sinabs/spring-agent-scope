package com.yoke.controller;

import java.util.Optional;
import java.util.UUID;

import com.yoke.a2a.RemoteAgentClient;
import com.yoke.api.RemoteChatApi;
import com.yoke.config.ConditionalOnRemoteAgent;
import com.yoke.model.ChatResponse;
import com.yoke.model.RemoteChatRequest;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@ConditionalOnRemoteAgent
public class RemoteChatController implements RemoteChatApi {

    private final RemoteAgentClient remote;

    @Override
    public Mono<ChatResponse> ask(RemoteChatRequest request) {
        String sessionId = Optional.ofNullable(request.sessionId())
                .filter(StringUtils::hasText)
                .orElseGet(() -> UUID.randomUUID().toString());
        return remote.ask(sessionId, request.message()).map(reply -> new ChatResponse(sessionId, reply));
    }
}
