package com.yoke.model;

/**
 * 对话响应。
 *
 * @param sessionId 本次对话所属的会话 ID，新建会话时由服务端生成，后续请求带上即可延续对话
 * @param reply     Agent 的回复文本
 */
public record ChatResponse(String sessionId, String reply) {
}
