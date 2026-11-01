# 接口使用

## 普通对话
向 POST /api/chat 发送 JSON，字段有可选的 userId、可选的 sessionId 和必填的 message。message 缺失、为空或纯空白会返回 400。响应里包含回复文本和本次对话的 sessionId，后续请求带上它就能延续对话。

## 流式对话
POST /api/chat/stream 以 SSE 的形式边推理边推送事件。事件名有 textDelta、toolCall、toolResult、hint、done 和 error。一次对话以 done 或 error 结尾，二者必有其一且只会出现一个。toolCall 和 toolResult 用相同的 id 对应。

## 并发的限制
同一个会话的并发请求不会被串行化，由调用方保证同一会话同一时间只发一个请求。目前也没有鉴权，userId 由调用方自报，不可信，不要把它当成身份校验。

## 运行环境
应用默认使用 local 环境，其他环境通过 SPRING_PROFILES_ACTIVE 切换。prod 环境必须提供 OPENAI_API_KEY 和 YOKE_REDIS_URI，缺失就启动失败，这是有意为之，避免带着空配置上线。
