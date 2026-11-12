# 工具与权限

## 模型能用哪些工具
Yoke 自带的工具有 get_current_time、calculate、list_my_notes、get_weather 和 retrieve。get_current_time 返回带时区偏移的当前时间；get_weather 查询城市的实时天气；calculate 做四则运算，支持括号和小数；list_my_notes 返回当前用户的笔记；retrieve 在 Yoke 自己的知识库里检索资料。此外还有 HarnessAgent 内置的 memory_save 等工具；memory_get、memory_search 和 session_* 这类会读到其他用户数据的工具已被禁用，模型看不到。

## 默认关闭了哪些能力
对外服务的 Agent 不开放文件读写、命令执行和联网工具，也就是 read_file、write_file、execute、web_fetch、web_search 这一类都看不到。子 Agent 不会继承联网工具的关闭，所以 Yoke 通过工具配置的 deny 名单把 web_fetch、web_search 以及上述记忆和会话读取类工具对所有子 Agent 一并禁掉。

## 业务上下文不经过模型
list_my_notes 需要知道当前用户是谁，但 userId 不是模型传的参数：它由控制器放进 RuntimeContext，工具方法里不带 @ToolParam 的 UserContext 参数由框架按类型注入。模型既看不到这个参数，也没法伪造。

## 检索如何降级
retrieve 工具背后是检索端口。配置了 embedding 模型时，向量检索和关键词检索并行，用 RRF 融合排名；没有配置或向量检索失败时，自动退回纯关键词检索。返回的每条资料前带方括号编号（形如 [文档名.md#片段编号]，是片段的稳定标识，多次检索也不会重复），回答时应原样照抄编号标注引用。

## 接入 MCP 服务
通过 yoke.mcp.servers 可以接入外部 MCP 服务，传输方式只允许 sse 和 streamable-http 两种 HTTP 传输。配置 stdio 会在启动时直接失败，因为 stdio 会让服务端按配置去启动本地进程。
