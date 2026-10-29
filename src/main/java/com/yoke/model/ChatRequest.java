package com.yoke.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 对话请求。
 *
 * @param sessionId 会话 ID，同一个 ID 共享对话历史；不传则由服务端新建会话，所以允许为空
 * @param message   用户输入，必填
 */
public record ChatRequest(String sessionId, @NotBlank(message = "message 不能为空") String message) {
}
