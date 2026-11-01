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

Yoke（轭）是套在牛马身上、让它们听使唤拉车的木架；Yoke 则是套在模型外面、让它能稳定干活的那层运行框架。它把 AgentScope 的 Agent 能力包装成一个开箱即用的 HTTP 服务，让你专注于业务，而不是胶水代码：

- 一个接口完成多轮对话：`POST /api/chat`；另有 `POST /api/chat/stream`，通过 SSE 边推理边推送。
- 每次请求新建临时 `HarnessAgent`，用完即关闭；对话历史保存在 Redis 里，重启不丢、多副本共享，按 `(userId, sessionId)` 隔离，对话过长时自动压缩。
- 工具、知识库与子 Agent：计算器、时钟、按用户隔离的笔记工具，基于内置知识库的 `retrieve` 工具（返回带 `[1][2]` 编号的引用），通过 HTTP 接入外部 MCP 服务，以及两个子 Agent（一个在代码里声明，一个由工作区 Markdown 文件声明）。Agent 仍不开放文件、命令和联网工具，日志中间件为每次调用记录耗时轨迹。
- 通过 AgentScope 官方 starter 接入各种模型：OpenAI 兼容协议（可对接 DashScope、DeepSeek 等）、Anthropic、Gemini、Ollama，换依赖和配置即可。
- local / test / prod 多环境配置；生产环境缺少密钥时启动即失败，避免带着空密钥上线。
- 默认开启虚拟线程，Tomcat 和 Spring 默认线程池都使用虚拟线程。
- 请求参数校验，缺少 `message` 时返回 400。

## 快速开始

需要 JDK 25 和 Docker（用于本地 Redis）。

```bash
docker compose up -d            # 先启动 Redis；没有 Redis 应用无法启动
sdk env                         # 切换到 JDK 25（使用 sdkman 时）
export OPENAI_API_KEY=sk-...
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

### 流式调用

`POST /api/chat/stream` 的请求体与上面相同，Agent 推理的同时就把事件推送出来（`text/event-stream`）。用 `curl -N` 关闭缓冲：

```bash
curl -N -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"sessionId": "demo", "message": "北京天气怎么样"}'
```

示意输出（来自桩模型）：

```
event:textDelta
data:{"text":"我来查一下天气。"}

event:toolCall
data:{"id":"call_1","name":"get_weather","arguments":"{\"city\":\"北京\"}"}

event:toolResult
data:{"id":"call_1","name":"get_weather","state":"success","output":"\"晴，25 度（北京）\""}

event:textDelta
data:{"text":"北京今天"}

event:textDelta
data:{"text":"晴，25 度。"}

event:done
data:{"sessionId":"demo","text":"北京今天晴，25 度。","usage":{"inputTokens":83,"outputTokens":27,"totalTokens":110}}
```

事件名是一个封闭集合：

- `textDelta`：模型生成的一段文本增量，按顺序拼接即得到回复
- `toolCall`：模型决定调用的工具，名称和入参已经攒齐
- `toolResult`：工具的执行结果，通过 `id` 与 `toolCall` 对应
- `hint`：框架在推理过程中追加的提示
- `done`：正常结束，带完整回复、`sessionId`（没传时由服务端生成）和累计 token 用量
- `error`：推理出错；HTTP 状态码仍是 200，流正常结束而不是断开连接。message 是原样的异常信息，未做脱敏，不要直接展示给终端用户

每次流都以 `done` 或 `error` 结尾。`message` 为空白时在建立流之前就返回 400。流正常结束、出错或客户端断开时，临时 Agent 都会被关闭。

> **注意**
> 同一会话的并发请求不会被串行化，请避免同时发送。`HarnessAgent.close()` 会在每个请求的清理回调里等待框架全局的异步写入静默（两处各最长 5 秒），请求之间因此耦合；尚未做并发压测。

## 配置

配置按环境拆分，没有显式指定环境时默认激活 `local`；部署时用环境变量 `SPRING_PROFILES_ACTIVE=prod` 或命令行参数 `--spring.profiles.active=prod` 切换。

- `application.yml`：各环境通用：模型、Agent、虚拟线程、日志格式、工作区、键前缀、压缩、知识库向量化配置和（注释形式的）MCP 示例
- `application-local.yml`：本地开发：各密钥读环境变量（可为空），Redis 连 `redis://127.0.0.1:6379`（即 `docker-compose.yml` 里的那个），`com.yoke` 日志为 debug
- `application-test.yml`：自动化测试：假密钥，不会真正调用模型；Redis 地址由测试注入
- `application-prod.yml`：生产：密钥和 Redis 地址（`YOKE_REDIS_URI`）必须由环境变量提供，缺失则启动失败；优雅停机

模型由 AgentScope 官方 starter 根据 `agentscope.*` 创建，`HarnessAgent` 由 `HarnessAgentFactory` 每次请求新建（官方的单例 Agent 已关闭）：

| 配置 | 说明 |
|---|---|
| `agentscope.openai.api-key` | 模型密钥，来自环境变量 `OPENAI_API_KEY` |
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
| `yoke.knowledge.embedding.model-name` / `api-key` / `base-url` / `dimensions` | 可选的向量化服务（OpenAI 兼容的 `/embeddings`），用于向量检索。`model-name` 和 `api-key`（`YOKE_EMBEDDING_API_KEY`）都有值才启用向量检索，否则只用关键词检索；运行中向量化服务出错时自动退回关键词检索 |
| `yoke.mcp.servers[]` | 外部 MCP 服务，默认一个也不接。每项有 `name`、`transport`（只能是 `sse` 或 `streamable-http`）、`url`、`timeout`（默认 30 秒）和可选的 `headers`。配置 `stdio` 会让应用启动失败；服务连不上同样启动失败 |

Agent 创建时不开放文件、命令和联网工具。读 2.0.4 源码发现子 Agent 只继承文件和 shell 的关闭、不继承联网工具的关闭，所以联网工具除了主 Agent 的 `disableWebTools()`，还用 `ToolsConfig` 的 deny 名单禁掉，它同时作用于主 Agent、内置的 `general-purpose` 子 Agent 和所有声明的子 Agent。显式传入 `ToolsConfig` 还会让 2.0.4 不再去读工作区里的 `tools.json`（那个文件可以声明 `stdio` MCP 服务，也就是在宿主机上启动进程）。模型看到的工具恰好是：Yoke 自己的（`get_current_time`、`calculate`、`list_my_notes`、`retrieve`），框架的记忆、会话、技能加载、异步结果、子 Agent 和后台任务类工具（`memory_save` 只往工作区固定的 `MEMORY.md` 与 `memory/<日期>.md` 追加，内容没有大小限制），再加上所配置的 MCP 服务公布的工具。`ToolPermissionTests` 用精确集合断言这份清单，框架升级新增了工具会让构建失败。容器里的 `MiddlewareBase` Bean 会挂到每个新建的 Agent 上；`LoggingMiddleware` 记录 Agent、模型、工具的耗时，不记录工具的参数和输出。OpenTelemetry 追踪默认不挂载：需要时自行引入 OpenTelemetry SDK 和导出器，再注册一个 `OtelTracingMiddleware` Bean。

## 工具、知识库与子 Agent

- **工具**（`com.yoke.tool`）：带 `@Tool` 方法的普通类，启动时一次性注册进共享的 `Toolkit`。`calculate` 用手写的递归下降解析器（不用脚本引擎，输入长度和嵌套深度有上限，除零以文本形式报告）。`list_my_notes` 的 `UserContext` 参数不带 `@ToolParam`：框架按类型从本次请求的 `RuntimeContext` 里注入，模型既看不到也伪造不了用户标识。
- **检索**：`retrieve` 工具检索 `src/main/resources/knowledge/*.md`，返回带 `[1]`、`[2]` 编号和来源的片段，并要求模型据此引用。它是唯一的检索通道，有意不同时使用框架的 `knowledge()` 自动注入。检索分层放在应用自己的 `RetrievePort` 后面（不含框架类型）：BM25 关键词检索永远可用；配置了向量化服务就再加一路向量检索，两路用 RRF（常数 60）融合，任何一路失败就用另一路。注意开启向量化后，用户的检索问题和知识库语料会发送给 embedding 服务提供方。没有使用框架自带的 `rag-simple`：`core/rag` 包在 2.0.4 里已 `@Deprecated(forRemoval = true)`，而且该模块会拖进 Qdrant、Milvus、Elasticsearch、PostgreSQL、PDFBox、POI、Tika 等一大批依赖。
- **MCP**：只接受 HTTP 传输。示例：

  ```yaml
  yoke:
    mcp:
      servers:
        - name: docs
          transport: streamable-http   # 或 sse
          url: https://mcp.example.com/mcp
          timeout: 30s
          headers:
            Authorization: Bearer ${MCP_DOCS_TOKEN}
  ```

  工具以服务端公布的名字出现。2.0.4 里 MCP 工具只有服务端声明了 `readOnlyHint` 才会免确认执行，而 Yoke 的 HTTP 接口没有人工确认通道（否则一轮对话会在工具调用处无声结束、回复为空）。所以只注册声明了只读的工具，其余跳过（工具名记在 INFO 日志里），主 Agent 和子 Agent 都看不到它们。
- **子 Agent**：`summarizer` 在代码里声明（`YokeSubagents`），没有任何工具；`kb-researcher` 由 `src/main/resources/workspace/subagents/kb-researcher.md` 声明，启动时拷贝到 `yoke.workspace/subagents/`，只能用 `retrieve`。2.0.4 对同 id 冲突是静默的：工作区文件会覆盖代码里的声明（`general-purpose.md` 还会替换内置的那个），所以 Yoke 会扫描 `yoke.workspace/subagents/` 里实际存在的全部文件（包括遗留的、手放的，不只是随应用发布的那几个），遇到任何这类重名都直接启动失败。

要换成其他模型，把 `build.gradle` 里的 `agentscope-openai-spring-boot-starter` 换成对应的 starter（dashscope、anthropic、gemini、ollama），并修改 `agentscope.model.provider`。

## 项目结构

```
src/main/java/com/yoke/
├── YokeApplication.java        应用入口
├── api/                        HTTP 接口契约（ChatApi，路径、校验和文档都在这里）
├── codec/                      领域类型与框架类型之间的转换（MessageCodec、EventCodec）
├── config/                     Agent、Redis、工具、知识库与子 Agent 配置（HarnessAgentFactory、YokeProperties、YokeSubagents）
├── controller/                 接口实现（ChatController）
├── knowledge/                  检索端口及实现：关键词、向量、RRF 融合（不含框架类型）
├── mcp/                        到外部 MCP 服务的连接（只支持 HTTP 传输）
├── middleware/                 Agent 中间件（LoggingMiddleware）
├── model/                      请求、响应与流式事件对象（ChatRequest、ChatResponse、ChatEvent）
└── tool/                       暴露给模型的工具（ChatTools、RetrieveTool、UserContext）
src/main/resources/             application.yml、各环境配置、knowledge/*.md 和 workspace/subagents/*.md
docker-compose.yml              本地 Redis，集成测试也用它
docs/brand/                     logo
```

## 了解更多

- [AgentScope Java 文档](https://java.agentscope.io)：Agent、工具、权限、状态存储等能力的完整说明。
- [AgentScope Java 快速开始](https://java.agentscope.io/v2/zh/docs/quickstart.md)。

## 参与贡献

欢迎提交 Issue 和 Pull Request。提交前请运行 `./gradlew build` 确保全部测试通过；代码注释使用中文。AgentScope 的消息和事件类型不能泄漏到 `codec` 之外（`middleware` 除外，它的钩子签名本身就要用这些类型），`knowledge` 包不能出现任何 AgentScope 类型，`ArchitectureTests` 会检查这些。

## 许可证

Yoke 是基于 [Apache License 2.0](LICENSE) 开源的软件。

## Star 趋势

[![Star History Chart](https://api.star-history.com/svg?repos=sinabs/spring-agent-scope&type=Date)](https://star-history.com/#sinabs/spring-agent-scope&Date)
