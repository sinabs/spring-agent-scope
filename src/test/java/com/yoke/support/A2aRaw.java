package com.yoke.support;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 直接用 HTTP 对 A2A 端点发原始 JSON-RPC，用来测试协议层行为（认证、校验、错误码）。 */
public final class A2aRaw {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Response(int status, String body, String wwwAuthenticate) {

        public JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("响应不是 JSON：" + body, e);
            }
        }

        /** 回复的文本（result.parts 里所有 text 拼起来）。 */
        public String text() {
            StringBuilder out = new StringBuilder();
            for (JsonNode part : json().path("result").path("parts")) {
                out.append(part.path("text").asText(""));
            }
            return out.toString();
        }

        public int errorCode() {
            return json().path("error").path("code").asInt(0);
        }
    }

    private final String base;
    private final String token;

    public A2aRaw(int port, String token) {
        this.base = "http://127.0.0.1:" + port;
        this.token = token;
    }

    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 构造 message/send 请求体；contextId 和 metadata 为 null 时不带。 */
    public static String sendBody(String text, String contextId, Map<String, Object> metadata) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("kind", "message");
        message.put("role", "user");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("parts", List.of(Map.of("kind", "text", "text", text)));
        if (Objects.nonNull(contextId)) {
            message.put("contextId", contextId);
        }
        if (Objects.nonNull(metadata)) {
            message.put("metadata", metadata);
        }
        return rpc("message/send", Map.of("message", message));
    }

    public static String rpc(String method, Map<String, Object> params) {
        try {
            return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "req-1", "method", method, "params", params));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public Response post(String body, String authorization) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/a2a"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (Objects.nonNull(authorization)) {
            request.header("Authorization", authorization);
        }
        return execute(request.build());
    }

    /** 带正确 token 发送。 */
    public Response send(String body) {
        return post(body, Optional.ofNullable(token).map(value -> "Bearer " + value).orElse(null));
    }

    /** 向任意路径 POST 一个 JSON（不带认证），用来测试 Yoke 自己的 HTTP 接口。 */
    public Response postJson(String path, String body) {
        return execute(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    /** 以 chunked（不声明 Content-Length）方式发送 POST /a2a，体是 {@code size} 个字节的空格。 */
    public Response postChunked(long size, String authorization) {
        @SuppressWarnings("resource") // 请求发送时由 HttpClient 读取并关闭
        java.io.InputStream stream = new java.io.InputStream() {
            private long left = size;

            @Override
            public int read() {
                return left-- > 0 ? ' ' : -1;
            }
        };
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/a2a"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> stream));
        if (Objects.nonNull(authorization)) {
            request.header("Authorization", authorization);
        }
        return execute(request.build());
    }

    public Response get(String path) {
        return execute(HttpRequest.newBuilder(URI.create(base + path)).GET().build());
    }

    private Response execute(HttpRequest request) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("WWW-Authenticate").orElse(null));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
