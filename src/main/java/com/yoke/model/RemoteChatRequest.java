package com.yoke.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 转发给远端 A2A Agent 的请求。只有会话和消息，没有任何能指定远端地址的字段。
 *
 * @param sessionId 远端会话 ID，不传则新建；格式见 {@link SafeId}
 * @param message   要发给远端的话，必填
 */
public record RemoteChatRequest(
        @SafeId String sessionId,
        @NotBlank(message = "message 不能为空") String message) {
}
