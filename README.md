<p align="center"><img src="docs/brand/logo.svg" width="160" alt="Yoke Logo"></p>

<p align="center">
<a href="https://github.com/sinabs/spring-agent-scope/actions/workflows/gradle.yml"><img src="https://github.com/sinabs/spring-agent-scope/actions/workflows/gradle.yml/badge.svg" alt="Build Status"></a>
<img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
<img src="https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F" alt="Spring Boot 4.1">
<img src="https://img.shields.io/badge/AgentScope%20Java-2.0-5B6BFF" alt="AgentScope Java 2.0">
<img src="https://img.shields.io/badge/Gradle-9.8-02303A" alt="Gradle 9.8">
<a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue" alt="License"></a>
</p>

<p align="center">English | <a href="README_CN.md">简体中文</a></p>

## About Yoke

**Yoke** · AgentHarness: a monolithic agent service built on Spring Boot and [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) (V2).

> A Spring-based agent harness.

A yoke is the wooden frame that makes oxen and horses pull in the direction you want. Yoke does the same for models: it is the runtime layer wrapped around a model so it can do real work reliably. It turns AgentScope's agent capabilities into a ready-to-use HTTP service, so you can focus on your business logic instead of glue code:

- One endpoint for multi-turn conversations: `POST /api/chat`, plus `POST /api/chat/stream` that streams the reasoning process over SSE using the AG-UI protocol.
- A built-in `get_weather` tool queries current weather through the open-source [Open-Meteo](https://open-meteo.com) API (no key needed; its free tier is for non-commercial use only).
- A temporary agent is built for every request and closed afterwards; conversation history lives in a shared state store and is isolated per session.
- Models are plugged in through the official AgentScope starters: OpenAI-compatible APIs (works with DashScope, DeepSeek and others), Anthropic, Gemini and Ollama. Switching is a dependency and configuration change.
- `local` / `test` / `prod` profiles. In production, a missing API key fails the startup instead of shipping with an empty key.
- Virtual threads are enabled by default, for both Tomcat and Spring's default thread pool.
- Request validation: a missing `message` returns 400.

## Getting Started

Requires JDK 25.

```bash
sdk env                         # switch to JDK 25 (if you use sdkman)
export ARK_API_KEY=...          # the local profile talks to Volcengine Ark (OpenAI-compatible)
./gradlew bootRun               # the local profile is active by default
```

```bash
curl -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "Hello"}'
# {"sessionId":"…","reply":"…"}
```

Send the returned `sessionId` with later requests to continue the same conversation; conversations with different `sessionId`s are isolated from each other. Run the tests with `./gradlew build`.

### Streaming (AG-UI)

`POST /api/chat/stream` speaks the [AG-UI](https://docs.ag-ui.com/concepts/events) protocol: the request body is a standard `RunAgentInput`, and the response is a `text/event-stream` of AG-UI events pushed while the agent is reasoning. Use `curl -N` to turn off buffering:

```bash
curl -N -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"threadId": "demo", "messages": [{"id": "msg-1", "role": "user", "content": "What is the weather in Beijing?"}]}'
```

Request rules (any violation returns 400 before the stream starts):

- `threadId` is the session ID and is required: letters, digits and `_.-`, 1 to 64 characters, not all dots. It shares the same history as `sessionId` of `POST /api/chat`
- `runId` is optional; the server generates a UUID when it is missing or blank
- `messages` must not be empty, every message needs an `id` and a `role`, and the last one must be a `user` message with non-blank plain text (multimodal content blocks are not supported)
- The server keeps the history itself and only uses the **last** `user` message. Earlier messages, `tools`, `context`, `state`, `forwardedProps` and `resume` are ignored, so a client cannot add tools to the agent or rewrite the history

Illustrative output (values are made up, field names come from real runs against a stub model, long fields are elided). Only `data:` lines are sent; the event type is the `type` field in the JSON, there is no SSE `event:` name:

```
data:{"type":"RUN_STARTED","threadId":"demo","runId":"…","input":{…}}

data:{"type":"TOOL_CALL_START","threadId":"demo","runId":"…","toolCallId":"call_1","toolCallName":"get_weather"}

data:{"type":"TOOL_CALL_ARGS","threadId":"demo","runId":"…","toolCallId":"call_1","delta":"{\"city\":\"Beijing\"}"}

data:{"type":"TOOL_CALL_END","threadId":"demo","runId":"…","toolCallId":"call_1"}

data:{"type":"TOOL_CALL_RESULT","threadId":"demo","runId":"…","toolCallId":"call_1","content":"\"Sunny, 25 C\"","role":"tool","messageId":"…"}

data:{"type":"TEXT_MESSAGE_START","threadId":"demo","runId":"…","messageId":"…","role":"assistant"}

data:{"type":"TEXT_MESSAGE_CONTENT","threadId":"demo","runId":"…","messageId":"…","delta":"It is sunny, 25 C."}

data:{"type":"TEXT_MESSAGE_END","threadId":"demo","runId":"…","messageId":"…"}

data:{"type":"CUSTOM","threadId":"demo","runId":"…","name":"token_usage","value":{"delta":{…},"cumulative":{"inputTokens":83,"outputTokens":27,…},"modelCall":{…}}}

data:{"type":"RUN_FINISHED","threadId":"demo","runId":"…"}
```

| Event `type` | Meaning |
|---|---|
| `RUN_STARTED` | Always the first event, carries `threadId` and `runId` |
| `TEXT_MESSAGE_START` / `TEXT_MESSAGE_CONTENT` / `TEXT_MESSAGE_END` | Generated text; concatenate the `delta` of the `CONTENT` events in order to get the reply |
| `TOOL_CALL_START` / `TOOL_CALL_ARGS` / `TOOL_CALL_END` | A tool the model decided to call; `TOOL_CALL_ARGS` carries fragments of the arguments JSON and only appears when the model streams the arguments in pieces |
| `TOOL_CALL_RESULT` | The result of a tool, matched to the call by `toolCallId` |
| `CUSTOM` with `name` = `token_usage` | Token usage after each model call, with `delta` and `cumulative` |
| `CUSTOM` with `name` = `hint` | A hint the framework adds during reasoning, `value` has `source` and `text` |
| `RUN_FINISHED` | The normal end |
| `RUN_ERROR` | Reasoning failed; the HTTP status is still 200 and the stream ends normally instead of dropping the connection. The message is the raw exception message and is not redacted, so do not show it to end users directly |

Every stream ends with either `RUN_FINISHED` or `RUN_ERROR`, and never both. There is no `done` event and the final text is not repeated: the client concatenates `TEXT_MESSAGE_CONTENT`. The temporary agent is closed when the stream completes, fails or the client disconnects. The adapter's `runTimeout` setting is not read by AgentScope 2.0.4, so it does not limit the run time.

> **Breaking change**
> The earlier private events (`textDelta`, `toolCall`, `toolResult`, `hint`, `done`, `error`) and the `{"sessionId", "message"}` request body of the streaming endpoint are gone. `POST /api/chat` is unchanged.

> **Note**
> The default in-memory state store only suits a single process, and history is lost when the service restarts. For multi-instance deployments, switch to a Redis or JDBC implementation. Concurrent requests for the same session are not serialized, so avoid sending them at the same time.

## Configuration

Configuration is split by profile. The `local` profile is active when none is specified; switch with the `SPRING_PROFILES_ACTIVE=prod` environment variable or the `--spring.profiles.active=prod` command-line argument.

- `application.yml`: Shared by all profiles: model, agent, virtual threads, log format
- `application-local.yml`: Local development: Volcengine Ark `base-url` and model name live in the file, the API key comes from `ARK_API_KEY` (may be empty), `com.yoke` logs at debug
- `application-test.yml`: Automated tests: a fake API key, the model is never really called
- `application-prod.yml`: Production: the API key must come from the environment, startup fails without it; graceful shutdown

The model is created by the official AgentScope starter from `agentscope.*`, and the agent is built per request by a shared builder (the official singleton agent is turned off):

| Property | Description |
|---|---|
| `agentscope.openai.api-key` | Model API key; `local` reads `ARK_API_KEY`, `prod` reads `OPENAI_API_KEY` |
| `agentscope.openai.model-name` | Model name |
| `agentscope.openai.base-url` | Set this to use another OpenAI-compatible service (DashScope, DeepSeek, ...) |
| `agentscope.agent.sys-prompt` | System prompt |
| `agentscope.agent.max-iters` | Maximum number of ReAct iterations |

To use another model, replace `agentscope-openai-spring-boot-starter` in `build.gradle` with the matching starter (dashscope, anthropic, gemini or ollama) and change `agentscope.model.provider`.

## Project Structure

```
src/main/java/com/yoke/
├── YokeApplication.java        Application entry point
├── api/                        HTTP contract (ChatApi: paths, validation and docs live here)
├── codec/                      Conversion between domain types and framework types (MessageCodec, AguiStreamCodec, HintEventConverter)
├── config/                     Shared agent configuration (builder, state store, AG-UI adapter)
├── controller/                 Contract implementation (ChatController)
└── model/                      Request and response objects (ChatRequest, ChatResponse)
src/main/resources/             application.yml and the per-profile configuration
docs/brand/                     Logo
```

## Learn More

- [AgentScope Java documentation](https://java.agentscope.io): the full guide to agents, tools, permissions, state stores and more.

## Contributing

Issues and pull requests are welcome. AgentScope types must not leak out of `codec`; `ArchitectureTests` enforces this. Before submitting, run `./gradlew build` and make sure all tests pass. Code comments are written in Chinese.

## License

Yoke is open-sourced software licensed under the [Apache License 2.0](LICENSE).

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=sinabs/spring-agent-scope&type=Date)](https://star-history.com/#sinabs/spring-agent-scope&Date)
