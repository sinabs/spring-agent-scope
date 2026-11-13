# 接口使用

## 普通对话
向 POST /api/chat 发送 JSON，字段有可选的 userId、可选的 sessionId 和必填的 message。message 缺失、为空或纯空白会返回 400。响应里包含回复文本和本次对话的 sessionId，后续请求带上它就能延续对话。

## 流式对话
POST /api/chat/stream 说 AG-UI 协议，以 SSE 的形式边推理边推送标准事件。请求体是 AG-UI 的 RunAgentInput，其中 threadId 就是会话 ID，服务端只采用最后一条 user 消息。事件类型写在每条 data 的 type 字段里，常见的有 RUN_STARTED、TEXT_MESSAGE_CONTENT、TOOL_CALL_START、TOOL_CALL_RESULT、RUN_FINISHED 和 RUN_ERROR。框架提示和 token 用量以 CUSTOM 事件发出，name 分别是 hint 和 token_usage。RUN_FINISHED 与 RUN_ERROR 二者只会出现一个，没有 done 事件，完整回复由客户端拼接各个 TEXT_MESSAGE_CONTENT 得到。TOOL_CALL_START 和 TOOL_CALL_RESULT 用相同的 toolCallId 对应。

## 并发的限制
同一个会话的并发请求不会被串行化，由调用方保证同一会话同一时间只发一个请求。目前也没有鉴权，userId 由调用方自报，不可信，不要把它当成身份校验。

## 运行环境
应用默认使用 local 环境，其他环境通过 SPRING_PROFILES_ACTIVE 切换。prod 环境必须提供 OPENAI_API_KEY 和 YOKE_REDIS_URI，缺失就启动失败，这是有意为之，避免带着空配置上线。
