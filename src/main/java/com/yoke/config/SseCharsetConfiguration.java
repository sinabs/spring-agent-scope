package com.yoke.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 给流式接口的响应类型补上 UTF-8。
 *
 * <p>{@code text/event-stream} 没有默认字符集，Spring 写出事件流时也不会带 charset，
 * IDEA 的 HTTP Client 等客户端就会按 ISO-8859-1 解码，中文显示成乱码（curl 不受影响）。
 * 在进入控制器之前先设好响应类型，Spring 写事件流时看到已经设置过就不会覆盖。
 */
@Configuration
public class SseCharsetConfiguration implements WebMvcConfigurer {

    private static final String SSE_UTF8 = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setContentType(SSE_UTF8);
                return true;
            }
        }).addPathPatterns("/api/chat/stream");
    }
}
