package com.yoke.tool;

/**
 * 当前请求的用户上下文，由控制器放进 {@code RuntimeContext}，工具方法里把它声明成<b>不带 @ToolParam 的参数</b>，
 * 框架就按类型从 {@code RuntimeContext} 里注入。
 *
 * <p>它不会出现在工具的 JSON Schema 里：模型既看不到这个参数，也没法在调用时伪造 userId。
 * 凡是「属于谁」的业务信息都应该走这条路，而不是让模型当参数传。
 *
 * @param userId 当前用户标识，目前由调用方自报（还没有鉴权）
 */
public record UserContext(String userId) {
}
