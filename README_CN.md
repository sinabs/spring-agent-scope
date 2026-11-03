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

- 一个接口完成多轮对话：`POST /api/chat`；另有 `POST /api/chat/stream`，按 AG-UI 协议通过 SSE 边推理边推送。
- 内置 `get_weather` 工具，通过开源的 [Open-Meteo](https://open-meteo.com) 接口查询当前天气（无需密钥，免费版仅限非商业使用）。
- 每次请求新建临时 Agent，用完即关闭；对话历史保存在共享的状态存储里，按会话隔离。
- 通过 AgentScope 官方 starter 接入各种模型：OpenAI 兼容协议（可对接 DashScope、DeepSeek 等）、Anthropic、Gemini、Ollama，换依赖和配置即可。
- local / test / prod 多环境配置；生产环境缺少密钥时启动即失败，避免带着空密钥上线。
- 默认开启虚拟线程，Tomcat 和 Spring 默认线程池都使用虚拟线程。
- 请求参数校验，缺少 `message` 时返回 400。

## 快速开始

需要 JDK 25。

```bash
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

后续请求带上返回的 `sessionId`，即可延续同一段对话；不同 `sessionId` 的对话历史互相隔离。运行测试：`./gradlew build`。

### 流式调用（AG-UI）

`POST /api/chat/stream` 使用 [AG-UI](https://docs.ag-ui.com/concepts/events) 协议：请求体是标准的 `RunAgentInput`，响应是 `text/event-stream`，Agent 推理的同时就把 AG-UI 事件推送出来。用 `curl -N` 关闭缓冲：

```bash
curl -N -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"threadId": "demo", "messages": [{"id": "msg-1", "role": "user", "content": "北京天气怎么样"}]}'
```

请求规则（任何一条不满足，都会在建立流之前返回 400）：

- `threadId` 就是会话 ID，必填：字母、数字、`_.-`，1~64 位，不能全是点。它与 `POST /api/chat` 的 `sessionId` 共用同一份历史
- `runId` 可省略；缺失或空白时由服务端生成 UUID
- `messages` 不能为空，每条消息都要有 `id` 和 `role`，最后一条必须是 `user` 消息且有非空白的纯文本（不支持多模态内容块）
- 对话历史由服务端保存，只采用**最后一条** `user` 消息；前面的消息、`tools`、`context`、`state`、`forwardedProps`、`resume` 都会被忽略，客户端不能借此给 Agent 加工具或改写历史

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

每次流都以 `RUN_FINISHED` 或 `RUN_ERROR` 结尾，二者不会同时出现。没有 `done` 事件，也不重复最终全文：由客户端拼接 `TEXT_MESSAGE_CONTENT`。流正常结束、出错或客户端断开时，临时 Agent 都会被关闭。适配器的 `runTimeout` 配置在 AgentScope 2.0.4 里没有被读取，所以它不能限制运行时长。

> **不兼容变更**
> 流式接口原先的私有事件（`textDelta`、`toolCall`、`toolResult`、`hint`、`done`、`error`）和 `{"sessionId", "message"}` 请求体已经移除。`POST /api/chat` 不变。

> **注意**
> 默认的内存状态存储只适合单进程，服务重启后历史会丢失；多副本部署时需要换成 Redis 或 JDBC 实现。同一会话的并发请求不会被串行化，请避免同时发送。

## 配置

配置按环境拆分，没有显式指定环境时默认激活 `local`；部署时用环境变量 `SPRING_PROFILES_ACTIVE=prod` 或命令行参数 `--spring.profiles.active=prod` 切换。

- `application.yml`：各环境通用：模型、Agent、虚拟线程、日志格式
- `application-local.yml`：本地开发：火山方舟的 `base-url` 与模型名写在文件里，密钥读环境变量 `ARK_API_KEY`（可为空），`com.yoke` 日志为 debug
- `application-test.yml`：自动化测试：假密钥，不会真正调用模型
- `application-prod.yml`：生产：密钥必须由环境变量提供，缺失则启动失败；优雅停机

模型由 AgentScope 官方 starter 根据 `agentscope.*` 创建，Agent 由共享 Builder 每次请求新建（官方的单例 Agent 已关闭）：

| 配置 | 说明 |
|---|---|
| `agentscope.openai.api-key` | 模型密钥；`local` 读 `ARK_API_KEY`，`prod` 读 `OPENAI_API_KEY` |
| `agentscope.openai.model-name` | 模型名称 |
| `agentscope.openai.base-url` | 接入其他 OpenAI 兼容服务（DashScope、DeepSeek 等）时设置 |
| `agentscope.agent.sys-prompt` | 系统提示词 |
| `agentscope.agent.max-iters` | ReAct 最大迭代次数 |

要换成其他模型，把 `build.gradle` 里的 `agentscope-openai-spring-boot-starter` 换成对应的 starter（dashscope、anthropic、gemini、ollama），并修改 `agentscope.model.provider`。

## 项目结构

```
src/main/java/com/yoke/
├── YokeApplication.java        应用入口
├── api/                        HTTP 接口契约（ChatApi，路径、校验和文档都在这里）
├── codec/                      领域类型与框架类型之间的转换（MessageCodec、AguiStreamCodec、HintEventConverter）
├── config/                     Agent 共享配置（Builder、状态存储、AG-UI 适配器）
├── controller/                 接口实现（ChatController）
└── model/                      请求与响应对象（ChatRequest、ChatResponse）
src/main/resources/             application.yml 及各环境配置
docs/brand/                     logo
```

## 了解更多

- [AgentScope Java 文档](https://java.agentscope.io)：Agent、工具、权限、状态存储等能力的完整说明。
- [AgentScope Java 快速开始](https://java.agentscope.io/v2/zh/docs/quickstart.md)。

## 参与贡献

欢迎提交 Issue 和 Pull Request。提交前请运行 `./gradlew build` 确保全部测试通过；代码注释使用中文。AgentScope 的类型不能泄漏到 `codec` 之外，`ArchitectureTests` 会检查这一点。

## 许可证

Yoke 是基于 [Apache License 2.0](LICENSE) 开源的软件。

## Star 趋势

[![Star History Chart](https://api.star-history.com/svg?repos=sinabs/spring-agent-scope&type=Date)](https://star-history.com/#sinabs/spring-agent-scope&Date)
