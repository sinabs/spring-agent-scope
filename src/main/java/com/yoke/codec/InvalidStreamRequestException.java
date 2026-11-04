package com.yoke.codec;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 流式请求体不合法。在建立流之前抛出，由 Spring MVC 转成 400，不会进入推理。
 *
 * <p>消息只描述哪一项不合法，不回显请求体内容。
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidStreamRequestException extends IllegalArgumentException {

    public InvalidStreamRequestException(String message) {
        super(message);
    }

    public InvalidStreamRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
