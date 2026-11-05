<p align="center"><img src="docs/brand/logo.svg" width="160" alt="Yoke Logo"></p>

<p align="center">
<a href="https://github.com/sinabs/spring-agent-scope/actions/workflows/gradle.yml"><img src="https://github.com/sinabs/spring-agent-scope/actions/workflows/gradle.yml/badge.svg" alt="Build Status"></a>
<img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
<img src="https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F" alt="Spring Boot 4.1">
<img src="https://img.shields.io/badge/AgentScope%20Java-2.0-5B6BFF" alt="AgentScope Java 2.0">
<img src="https://img.shields.io/badge/Gradle-9.8-02303A" alt="Gradle 9.8">
<a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue" alt="License"></a>
</p>

<p align="center"><a href="README.md">English</a> | 简体中文</p>

## 关于 Yoke

**Yoke** · AgentHarness：基于 Spring Boot 和 [AgentScope Java](https://github.com/agentscope-ai/agentscope-java)（V2）的单体 Agent 服务。

> A Spring-based agent harness.

Yoke 是友客AI的开源项目：大家一起学习 AgentScope Java V2 和 Spring 的源码与用法，在实践中开发出一个真正能用的 Agent 服务。Yoke（轭）原指套在牛马身上、让它们朝着正确方向使劲的木架，Yoke 也是如此：它是套在模型外面、让模型稳定干活的那层运行框架，把 AgentScope 的 Agent 能力包装成开箱即用的 HTTP 服务。

- 想用 AgentScope Java 做个 Agent 服务，Demo 十几行就能跑，可一到"要给用户用"，问题就来了：每个请求一个 Agent 还是全局一个？对话历史放哪？
- 流式输出、工具调用、多轮会话、权限收紧，这些在官方文档里是分散的例子，拼到一个 Spring Boot 项目里，到底该怎么分层、守哪些边界？
- 官方的 starter 能一行引入，可它的入口没有认证、Agent 是单例，直接上线会出什么事？哪些该用，哪些该自己写？
- 这个项目从一个能对话的接口开始，一课加一层，每一课只解决一个工程问题，代码开源、可以直接运行，也会如实写下没验证的限制和踩过的坑。

### 特性

- 一个接口完成多轮对话：`POST /api/chat`；另有 `POST /api/chat/stream`，按 AG-UI 协议通过 SSE 边推理边推送。
- 内置 `get_weather` 工具，通过开源的 [Open-Meteo](https://open-meteo.com) 接口查询当前天气（无需密钥，免费版仅限非商业使用）。
- 每次请求新建临时 `HarnessAgent`，用完即关闭；对话历史保存在 Redis 里，重启不丢、多副本共享，按 `(userId, sessionId)` 隔离，对话过长时自动压缩。
- 除 `get_weather` 外 Agent 没有其他工具：不开放文件、命令和联网，子 Agent 整体关闭；日志中间件为每次调用记录耗时轨迹。
- 通过 AgentScope 官方 starter 接入各种模型：OpenAI 兼容协议（可对接 DashScope、DeepSeek 等）、Anthropic、Gemini、Ollama，换依赖和配置即可。
- local / test / prod 多环境配置；生产环境缺少密钥时启动即失败，避免带着空密钥上线。
- 默认开启虚拟线程，Tomcat 和 Spring 默认线程池都使用虚拟线程。

## 快速开始

需要 JDK 25 和 Docker（用于本地 Redis）。

```bash
docker compose up -d            # 先启动 Redis；没有 Redis 应用无法启动
sdk env                         # 切换到 JDK 25（使用 sdkman 时）
export ARK_API_KEY=...          # local 环境接火山方舟（OpenAI 兼容协议）
./gradlew bootRun               # 默认激活 local 环境
```

```bash
curl -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "你好"}'
# {"sessionId":"…","reply":"…"}
```

后续请求带上返回的 `sessionId`，即可延续同一段对话。运行测试：`./gradlew build`；需要真实 Redis 的测试会通过 Testcontainers 读取同一份 `docker-compose.yml` 拉起，Docker 不可用时会明确跳过并写明原因（`CI=true` 时改为失败）。

### 会话

对话历史按 `(userId, sessionId)` 存放。请求体里两者都是可选的（`{"userId": "alice", "sessionId": "s-1", "message": "你好"}`），服务端保证二者到达 Agent 时都有值：

- 两个都传：用这一对
- 只传 `sessionId`：归属 `default` 用户（与旧版客户端行为一致）
- 只传 `userId`：为该用户新建一个会话，新的会话 ID 在响应里返回
- 都不传：`default` 用户下新建会话

不会把「只给其一」直接交给框架：AgentScope 2.0.4 里缺 `sessionId` 会落到以 Agent 名称命名的共享会话，缺 `userId` 会落到匿名用户，所有调用方的历史会混在一起。两个标识只能包含字母、数字、`_`、`.`、`-`，长度 1 到 64，且不能全是点（`.`、`..` 会被拒绝），否则返回 400。原因是框架把槽位拼成 `userId/sessionId`，允许 `/` 会让不同的一对撞到同一个槽位，`userId` 还会成为工作区的目录名。目前没有鉴权，`userId` 由调用方自报，不是身份校验。

每个会话在 Redis 里存了什么（用真实模型实测：两轮对话，其中一轮调用了工具）：

| 键 | 类型 | 内容 |
|---|---|---|
| `yoke:session:{userId}/{sessionId}:agent_state` | String | 一份 JSON，是整个 Agent 状态。对话在它的 `context` 数组里（一条消息一项：`USER`、带 `tool_use` 的 `ASSISTANT`、带 `tool_result` 的 `TOOL` 等），旁边是 `summary`（压缩后的摘要落点）和几个空的上下文对象。两轮约 3.3 KB |
| `yoke:session:{userId}/{sessionId}:agent_state:ver` | String | 乐观并发用的版本号，每次保存递增 |
| `yoke:session:{userId}/{sessionId}:_keys` | Set | 该会话下所有状态键的名字（这里只有 `agent_state`），删除整个会话时靠它 |

模型的 `thinking` 内容和每条消息的 token 用量也会一并存下；每一轮都是整份读出、整份写回，所以会随对话变长而变大。没有单独的消息列表键。


### 流式调用（AG-UI）

`POST /api/chat/stream` 使用 [AG-UI](https://docs.ag-ui.com/concepts/events) 协议：请求体是标准的 `RunAgentInput`，响应是 `text/event-stream`，Agent 推理的同时就把 AG-UI 事件推送出来。用 `curl -N` 关闭缓冲：

```bash
curl -N -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"threadId": "demo", "runId": "run-1", "messages": [{"id": "msg-1", "role": "user", "content": "北京天气怎么样"}]}'
```

请求规则（任何一条不满足，都会在建立流之前返回 400）：

- `Content-Type` 必须是 `application/json`，否则返回 415。这也挡住了跨站页面用不触发 CORS 预检的 `text/plain` 请求发起对话
- `threadId` 就是会话 ID，必填：字母、数字、`_.-`，1~64 位，不能全是点。它与 `POST /api/chat` 的 `sessionId` 共用同一份历史（`default` 用户下；`RunAgentInput` 没有用户字段，流式接口固定以 `default` 用户运行）
- `runId` 必填且不能为空白（`RunAgentInput` 要求非空）
- `messages` 不能为空，每条消息都要有 `id` 和 `role`，最后一条必须是 `user` 消息且有非空白的纯文本（不支持多模态内容块）
- 对话历史由服务端保存，只采用**最后一条** `user` 消息；前面的消息、`tools`、`context`、`state`、`forwardedProps`、`resume` 都会被忽略，客户端不能借此给 Agent 加工具或改写历史。这条消息也只取文本：它的 `id`、`toolCalls`、`toolCallId` 不会进入历史，消息 ID 由服务端生成。适配器还配置了 `ToolMergeMode.AGENT_ONLY`，只会用服务端注册的工具

示意输出（取值是编的，字段名来自桩模型的实际运行，较长的字段做了省略）。只发 `data:` 行，事件类型是 JSON 里的 `type` 字段，没有 SSE 的 `event:` 名：

```
data:{"type":"RUN_STARTED","threadId":"demo","runId":"…","input":{…}}

data:{"type":"TOOL_CALL_START","threadId":"demo","runId":"…","toolCallId":"call_1","toolCallName":"get_weather"}

data:{"type":"TOOL_CALL_ARGS","threadId":"demo","runId":"…","toolCallId":"call_1","delta":"{\"city\":\"北京\"}"}

data:{"type":"TOOL_CALL_END","threadId":"demo","runId":"…","toolCallId":"call_1"}

data:{"type":"TOOL_CALL_RESULT","threadId":"demo","runId":"…","toolCallId":"call_1","content":"\"晴，25 度（北京）\"","role":"tool","messageId":"…"}

data:{"type":"TEXT_MESSAGE_START","threadId":"demo","runId":"…","messageId":"…","role":"assistant"}

data:{"type":"TEXT_MESSAGE_CONTENT","threadId":"demo","runId":"…","messageId":"…","delta":"北京今天晴，25 度。"}

data:{"type":"TEXT_MESSAGE_END","threadId":"demo","runId":"…","messageId":"…"}

data:{"type":"CUSTOM","threadId":"demo","runId":"…","name":"token_usage","value":{"delta":{…},"cumulative":{"inputTokens":83,"outputTokens":27,…},"modelCall":{…}}}

data:{"type":"RUN_FINISHED","threadId":"demo","runId":"…"}
```

| 事件 `type` | 含义 |
|---|---|
| `RUN_STARTED` | 总是第一个事件，带 `threadId` 和 `runId` |
| `TEXT_MESSAGE_START` / `TEXT_MESSAGE_CONTENT` / `TEXT_MESSAGE_END` | 模型生成的文本；按顺序拼接 `CONTENT` 事件的 `delta` 即得到回复 |
| `TOOL_CALL_START` / `TOOL_CALL_ARGS` / `TOOL_CALL_END` | 模型决定调用的工具；`TOOL_CALL_ARGS` 的 `delta` 是入参 JSON 的片段，只有模型分片返回入参时才会出现 |
| `TOOL_CALL_RESULT` | 工具的执行结果，通过 `toolCallId` 与调用对应 |
| `CUSTOM`，`name` 为 `token_usage` | 每次模型调用结束后的 token 用量，含 `delta` 和 `cumulative` |
| `CUSTOM`，`name` 为 `hint` | 框架在推理过程中追加的提示，`value` 里有 `source` 和 `text` |
| `RUN_FINISHED` | 正常结束 |
| `RUN_ERROR` | 推理出错；HTTP 状态码仍是 200，流正常结束而不是断开连接。message 是原样的异常信息，未做脱敏，不要直接展示给终端用户 |

每次流都以 `RUN_STARTED` 开头（创建 Agent 失败时也一样），以 `RUN_FINISHED` 或 `RUN_ERROR` 结尾，二者不会同时出现。Agent 运行中出错时，`RUN_ERROR` 的 `code` 由 AG-UI 适配器按异常类型映射（`TIMEOUT_ERROR`、`INTERRUPTED_ERROR`、`INVALID_INPUT_ERROR` 或 `INTERNAL_ERROR`）；适配器之外的失败（例如创建 Agent 失败）一律是 `INTERNAL_ERROR`。没有 `done` 事件，也不重复最终全文：由客户端拼接 `TEXT_MESSAGE_CONTENT`。流正常结束、出错或客户端断开时，临时 Agent 都会被关闭。适配器的 `runTimeout` 配置在 AgentScope 2.0.4 里没有被读取，所以它不能限制运行时长。

> **不兼容变更**
> 流式接口原先的私有事件（`textDelta`、`toolCall`、`toolResult`、`hint`、`done`、`error`）和 `{"sessionId", "message"}` 请求体已经移除。流式接口现在只接受 `Content-Type: application/json`，不带 `Content-Type`、或者用 `text/plain`、`application/*+json` 的请求都会返回 415。`POST /api/chat` 的 `sessionId` 现在按 `threadId` 的同一条规则校验（以前 `user:42` 这类非空白值能通过，现在返回 400）；空白的 `sessionId` 仍然新建会话。

> **注意**
> 同一会话的并发请求不会被串行化，请避免同时发送。`HarnessAgent.close()` 会在每个请求的清理回调里等待框架全局的异步写入静默（两处各最长 5 秒），请求之间因此耦合；尚未做并发压测。

## 配置

配置按环境拆分，没有显式指定环境时默认激活 `local`；部署时用环境变量 `SPRING_PROFILES_ACTIVE=prod` 或命令行参数 `--spring.profiles.active=prod` 切换。

- `application.yml`：各环境通用：模型、Agent、虚拟线程、日志格式、工作区、键前缀和压缩配置
- `application-local.yml`：本地开发：火山方舟的 `base-url` 与模型名写在文件里，密钥读环境变量 `ARK_API_KEY`（可为空），Redis 连 `redis://127.0.0.1:6379`（即 `docker-compose.yml` 里的那个），`com.yoke` 日志为 debug
- `application-test.yml`：自动化测试：假密钥，不会真正调用模型；Redis 地址由测试注入
- `application-prod.yml`：生产：密钥和 Redis 地址（`YOKE_REDIS_URI`）必须由环境变量提供，缺失则启动失败；优雅停机

模型由 AgentScope 官方 starter 根据 `agentscope.*` 创建，`HarnessAgent` 由 `HarnessAgentFactory` 每次请求新建（官方的单例 Agent 已关闭）：

| 配置 | 说明 |
|---|---|
| `agentscope.openai.api-key` | 模型密钥；`local` 读 `ARK_API_KEY`，`prod` 读 `OPENAI_API_KEY` |
| `agentscope.openai.model-name` | 模型名称 |
| `agentscope.openai.base-url` | 接入其他 OpenAI 兼容服务（DashScope、DeepSeek 等）时设置 |
| `agentscope.agent.sys-prompt` | 系统提示词 |
| `agentscope.agent.max-iters` | ReAct 最大迭代次数 |

Yoke 自己的配置使用 `yoke.*` 前缀：

| 配置 | 说明 |
|---|---|
| `yoke.redis.uri` | Redis 连接串。`prod` 下没有默认值（`YOKE_REDIS_URI`）；启动时连不上 Redis，应用直接启动失败 |
| `yoke.redis.key-prefix` | 所有 Redis 键的前缀，默认 `yoke:` |
| `yoke.compaction.trigger-messages` | 对话消息达到这个条数就压缩，默认 30 |
| `yoke.compaction.keep-messages` | 压缩后原样保留的最近条数，默认 10。小于等于 0，或保留条数不小于触发条数时，回落到默认值 |
| `yoke.workspace` | `HarnessAgent` 的工作区，放本地文件（转存的消息、记忆），默认 `~/.yoke/workspace`；只在本机，副本之间不共享 |

Agent 创建时不开放文件、命令和联网工具，并且整体关闭子 Agent：读 2.0.4 源码发现子 Agent 只继承文件和 shell 的关闭、不继承联网工具的关闭，开着子 Agent 就能让模型派生出能抓取任意 URL 的子 Agent。关闭后剩下的只有记忆和会话类工具（`memory_save` 只往工作区固定的 `MEMORY.md` 与 `memory/<日期>.md` 追加，内容没有大小限制）、`load_skill_through_path` 和 `wait_async_results`。容器里的 `MiddlewareBase` Bean 会挂到每个新建的 Agent 上；`LoggingMiddleware` 记录 Agent、模型、工具的耗时，不记录工具的参数和输出。OpenTelemetry 追踪默认不挂载：需要时自行引入 OpenTelemetry SDK 和导出器，再注册一个 `OtelTracingMiddleware` Bean。

要换成其他模型，把 `build.gradle` 里的 `agentscope-openai-spring-boot-starter` 换成对应的 starter（dashscope、anthropic、gemini、ollama），并修改 `agentscope.model.provider`。

## 项目结构

```
src/main/java/com/yoke/
├── YokeApplication.java        应用入口
├── api/                        HTTP 接口契约（ChatApi，路径、校验和文档都在这里）
├── codec/                      领域类型与框架类型之间的转换（MessageCodec、AguiStreamCodec、HintEventConverter）
├── config/                     Agent、Redis 与 AG-UI 适配器配置（HarnessAgentFactory、YokeProperties）
├── controller/                 接口实现（ChatController）
├── middleware/                 Agent 中间件（LoggingMiddleware）
└── model/                      请求与响应对象（ChatRequest、ChatResponse）
src/main/resources/             application.yml 及各环境配置
docker-compose.yml              本地 Redis，集成测试也用它
docs/brand/                     logo
```

## 了解更多

- [AgentScope Java 文档](https://java.agentscope.io)：Agent、工具、权限、状态存储等能力的完整说明。
- [AgentScope Java 快速开始](https://java.agentscope.io/v2/zh/docs/quickstart.md)。

## 参与贡献

欢迎提交 Issue 和 Pull Request。提交前请运行 `./gradlew build` 确保全部测试通过；代码注释使用中文。AgentScope 的消息和事件类型不能泄漏到 `codec` 之外（`middleware` 除外，它的钩子签名本身就要用这些类型），`ArchitectureTests` 会检查这一点。

## 许可证

Yoke 是基于 [Apache License 2.0](LICENSE) 开源的软件。

## Star 趋势

[![Star History Chart](https://api.star-history.com/svg?repos=sinabs/spring-agent-scope&type=Date)](https://star-history.com/#sinabs/spring-agent-scope&Date)
