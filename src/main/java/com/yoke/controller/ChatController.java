package com.yoke.controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.yoke.api.ChatApi;
import com.yoke.model.ChatRequest;
import com.yoke.model.ChatResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChatController implements ChatApi {

    private static final String DEFAULT_USER_ID = "default";

    private final ReActAgent.Builder agentBuilder;

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {

        String sessionId = Optional.ofNullable(request.sessionId())
                .filter(StringUtils::hasText)
                .orElseGet(() -> UUID.randomUUID().toString());

        RuntimeContext context = RuntimeContext.builder()
                .userId(DEFAULT_USER_ID)
                .sessionId(sessionId)
                .build();

        return Mono.using(
                agentBuilder::build,
                agent -> agent.call(List.of(new UserMessage(request.message())), context)
                        .map(reply -> new ChatResponse(sessionId, reply.getTextContent())),
                ReActAgent::close);
    }
}
