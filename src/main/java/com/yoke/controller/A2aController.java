package com.yoke.controller;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import com.yoke.a2a.A2aGateway;
import com.yoke.api.A2aApi;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "yoke.a2a.server", name = "enabled", havingValue = "true")
public class A2aController implements A2aApi {

    private final A2aGateway gateway;

    @Override
    public ResponseEntity<String> agentCard() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(gateway.agentCardJson());
    }

    @Override
    public ResponseEntity<String> message(HttpServletRequest request) {
        Map<String, String> headers = headersOf(request);
        A2aGateway.Reply reply = gateway.preflight(headers, request.getContentLengthLong());
        if (Objects.isNull(reply)) {
            String body;
            try {
                body = gateway.readBody(request.getInputStream());
            } catch (IOException e) {
                return respond(gateway.unreadable());
            }
            reply = Optional.ofNullable(body).map(text -> gateway.handle(text, headers)).orElseGet(gateway::tooLarge);
        }
        return respond(reply);
    }

    private static ResponseEntity<String> respond(A2aGateway.Reply reply) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.valueOf(reply.status()))
                .contentType(MediaType.APPLICATION_JSON);
        if (Objects.nonNull(reply.wwwAuthenticate())) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, reply.wwwAuthenticate());
        }
        return response.body(reply.body());
    }

    private static Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        return new HashMap<>(headers);
    }
}
