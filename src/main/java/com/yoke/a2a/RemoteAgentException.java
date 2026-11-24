package com.yoke.a2a;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 调用远端 A2A Agent 失败。reason 是给调用方看的一句中文说明，不含地址、凭证和远端返回的原文；
 * 底层原因挂在 cause 上，只进服务端日志。超时对应 504，其余对应 502。
 * Spring Boot 默认不把 reason 写进错误响应体（{@code server.error.include-message} 默认 never）。
 */
public class RemoteAgentException extends ResponseStatusException {

    public RemoteAgentException(String reason, boolean timeout, Throwable cause) {
        super(timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY, reason, cause);
    }
}
