# 会话与存储

## 对话历史存在哪里
对话历史不放在 Agent 实例里，而是放在 Redis。由于每个请求都会新建 Agent，新的实例要靠共享存储才能接上之前的对话。Yoke 使用 AgentScope 的 RedisDistributedStore，按 userId 和 sessionId 组成的会话槽位保存历史，所以服务重启之后历史仍然在。

## 会话标识的规则
请求里的 userId 和 sessionId 必须成对非空才对应一个会话。只传 sessionId 时归属 default 用户；只传 userId 时为该用户新建一个会话，新的会话 ID 在响应里返回；都不传时在 default 用户下新建会话。标识只能包含字母、数字、下划线、点和短横线，长度 1 到 64。

## 启动时检查 Redis
应用启动时会 ping 一次 Redis，连不上就直接启动失败，而不是等到第一个请求才发现历史存不下来。连接串通过 yoke.redis.uri 配置，生产环境来自环境变量 YOKE_REDIS_URI，没有默认值。

## 对话压缩
对话消息达到 yoke.compaction.trigger-messages 条（默认 30）就会触发压缩，压缩后原样保留最近 yoke.compaction.keep-messages 条（默认 10）。压缩前的消息会转存到工作区目录。
