package com.yoke.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 对话请求。
 *
 * @param sessionId 会话 ID，同一个 (userId, sessionId) 共享对话历史；不传则由服务端新建会话，所以允许为空；格式见 {@link SafeId}
 * @param message   用户输入，必填
 * @param userId    用户标识，不传时使用 {@code default}，格式见 {@link SafeId}；暂时没有鉴权，调用方自行保证它可信
 */
public record ChatRequest(
        @SafeId String sessionId,
        @NotBlank(message = "message 不能为空") String message,
        @SafeId String userId) {

    /** 不指定用户，落在 {@code default} 用户下。 */
    public ChatRequest(String sessionId, String message) {
        this(sessionId, message, null);
    }
}
