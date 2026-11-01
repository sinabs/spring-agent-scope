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
- 每次请求新建临时 Agent，用完即关闭；对话历史保存在共享的状态存储里，按会话隔离。
- 通过 AgentScope 官方 starter 接入各种模型：OpenAI 兼容协议（可对接 DashScope、DeepSeek 等）、Anthropic、Gemini、Ollama，换依赖和配置即可。
- local / test / prod 多环境配置；生产环境缺少密钥时启动即失败，避免带着空密钥上线。
- 默认开启虚拟线程，Tomcat 和 Spring 默认线程池都使用虚拟线程。
- 请求参数校验，缺少 `message` 时返回 400。

## 快速开始

需要 JDK 25。

```bash
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

后续请求带上返回的 `sessionId`，即可延续同一段对话；不同 `sessionId` 的对话历史互相隔离。运行测试：`./gradlew build`。

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
> 默认的内存状态存储只适合单进程，服务重启后历史会丢失；多副本部署时需要换成 Redis 或 JDBC 实现。同一会话的并发请求不会被串行化，请避免同时发送。

## 配置

配置按环境拆分，没有显式指定环境时默认激活 `local`；部署时用环境变量 `SPRING_PROFILES_ACTIVE=prod` 或命令行参数 `--spring.profiles.active=prod` 切换。

- `application.yml`：各环境通用：模型、Agent、虚拟线程、日志格式
- `application-local.yml`：本地开发：密钥读环境变量（可为空），`com.yoke` 日志为 debug
- `application-test.yml`：自动化测试：假密钥，不会真正调用模型
- `application-prod.yml`：生产：密钥必须由环境变量提供，缺失则启动失败；优雅停机

模型由 AgentScope 官方 starter 根据 `agentscope.*` 创建，Agent 由共享 Builder 每次请求新建（官方的单例 Agent 已关闭）：

| 配置 | 说明 |
|---|---|
| `agentscope.openai.api-key` | 模型密钥，来自环境变量 `OPENAI_API_KEY` |
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
├── codec/                      领域类型与框架类型之间的转换（MessageCodec、EventCodec）
├── config/                     Agent 共享配置（Builder、状态存储）
├── controller/                 接口实现（ChatController）
└── model/                      请求、响应与流式事件对象（ChatRequest、ChatResponse、ChatEvent）
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
