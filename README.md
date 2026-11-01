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

- One endpoint for multi-turn conversations: `POST /api/chat`, plus `POST /api/chat/stream` that streams the reasoning process over SSE.
- A temporary `HarnessAgent` is built for every request and closed afterwards; conversation history lives in Redis, so it survives restarts and is shared by all replicas. History is isolated per `(userId, sessionId)`, and long conversations are compacted automatically.
- The agent has no file, shell or web tools and sub-agents are switched off, and a logging middleware records a timing trace for every call.
- Models are plugged in through the official AgentScope starters: OpenAI-compatible APIs (works with DashScope, DeepSeek and others), Anthropic, Gemini and Ollama. Switching is a dependency and configuration change.
- `local` / `test` / `prod` profiles. In production, a missing API key fails the startup instead of shipping with an empty key.
- Virtual threads are enabled by default, for both Tomcat and Spring's default thread pool.
- Request validation: a missing `message` returns 400.

## Getting Started

Requires JDK 25 and Docker (for the local Redis).

```bash
docker compose up -d            # start Redis first; the app refuses to start without it
sdk env                         # switch to JDK 25 (if you use sdkman)
export OPENAI_API_KEY=sk-...
./gradlew bootRun               # the local profile is active by default
```

```bash
curl -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "Hello"}'
# {"sessionId":"…","reply":"…"}
```

Send the returned `sessionId` with later requests to continue the same conversation. Run the tests with `./gradlew build`; tests that need a real Redis start it from the same `docker-compose.yml` through Testcontainers, and are skipped with a clear reason when Docker is not available (with `CI=true` they fail instead).

### Sessions

History is stored per `(userId, sessionId)`. Both are optional in the request body (`{"userId": "alice", "sessionId": "s-1", "message": "Hello"}`), and the server always makes sure both have a value before the agent sees them:

- both given: that pair
- only `sessionId`: it belongs to the `default` user (same as older clients)
- only `userId`: a new session is created for that user, and its id is returned in the response
- neither: a new session under the `default` user

A half-empty pair is never passed on to the framework, because in AgentScope 2.0.4 a missing `sessionId` falls into one shared session named after the agent, and a missing `userId` falls into an anonymous user, which would mix everyone's history together. Both ids may only contain letters, digits, `_`, `.` and `-` (1 to 64 characters, not all dots, so `.` and `..` are rejected); anything else returns 400. The framework joins them as `userId/sessionId`, so allowing `/` would let different pairs collide on one slot, and `userId` also becomes a workspace directory name. There is no authentication yet: `userId` is whatever the caller says, so it is not an identity check.

### Streaming

`POST /api/chat/stream` takes the same request body and pushes events (`text/event-stream`) while the agent is reasoning. Use `curl -N` to turn off buffering:

```bash
curl -N -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"sessionId": "demo", "message": "What is the weather in Beijing?"}'
```

Illustrative output (from a stub model):

```
event:textDelta
data:{"text":"Let me check."}

event:toolCall
data:{"id":"call_1","name":"get_weather","arguments":"{\"city\":\"Beijing\"}"}

event:toolResult
data:{"id":"call_1","name":"get_weather","state":"success","output":"\"Sunny, 25 C\""}

event:textDelta
data:{"text":"It is sunny, 25 C."}

event:done
data:{"sessionId":"demo","text":"It is sunny, 25 C.","usage":{"inputTokens":83,"outputTokens":27,"totalTokens":110}}
```

The event names form a closed set:

- `textDelta`: a chunk of generated text; concatenate them in order to get the reply
- `toolCall`: a tool the model decided to call, with its name and arguments already assembled
- `toolResult`: the result of a tool, matched to its `toolCall` by `id`
- `hint`: a hint the framework adds during reasoning
- `done`: the normal end, with the full reply, the `sessionId` (generated by the server if you did not send one) and the accumulated token usage
- `error`: reasoning failed; the HTTP status is still 200 and the stream ends normally instead of dropping the connection. The message is the raw exception message and is not redacted, so do not show it to end users directly

Every stream ends with either `done` or `error`. A blank `message` returns 400 before the stream starts. The temporary agent is closed when the stream completes, fails or the client disconnects.

> **Note**
> Concurrent requests for the same session are not serialized, so avoid sending them at the same time. `HarnessAgent.close()` waits for the framework's global async-write quiescence (up to 5 seconds each for two waits) inside the per-request cleanup, so requests are coupled through it; this has not been load-tested.

## Configuration

Configuration is split by profile. The `local` profile is active when none is specified; switch with the `SPRING_PROFILES_ACTIVE=prod` environment variable or the `--spring.profiles.active=prod` command-line argument.

- `application.yml`: Shared by all profiles: model, agent, virtual threads, log format, workspace, key prefix and compaction
- `application-local.yml`: Local development: API key from the environment (may be empty), Redis at `redis://127.0.0.1:6379` (the one from `docker-compose.yml`), `com.yoke` logs at debug
- `application-test.yml`: Automated tests: a fake API key, the model is never really called; the Redis address is injected by the tests
- `application-prod.yml`: Production: the API key and the Redis address (`YOKE_REDIS_URI`) must come from the environment, startup fails without them; graceful shutdown

The model is created by the official AgentScope starter from `agentscope.*`, and a `HarnessAgent` is built per request by `HarnessAgentFactory` (the official singleton agent is turned off):

| Property | Description |
|---|---|
| `agentscope.openai.api-key` | Model API key, taken from the `OPENAI_API_KEY` environment variable |
| `agentscope.openai.model-name` | Model name |
| `agentscope.openai.base-url` | Set this to use another OpenAI-compatible service (DashScope, DeepSeek, ...) |
| `agentscope.agent.sys-prompt` | System prompt |
| `agentscope.agent.max-iters` | Maximum number of ReAct iterations |

Yoke's own settings use the `yoke.*` prefix:

| Property | Description |
|---|---|
| `yoke.redis.uri` | Redis connection string. No default in `prod` (`YOKE_REDIS_URI`); the application fails at startup if Redis cannot be reached |
| `yoke.redis.key-prefix` | Prefix of every Redis key, default `yoke:` |
| `yoke.compaction.trigger-messages` | Compact the conversation once it reaches this many messages, default 30 |
| `yoke.compaction.keep-messages` | Messages kept verbatim after a compaction, default 10. Values `<= 0`, or a keep count not smaller than the trigger count, fall back to the defaults |
| `yoke.workspace` | `HarnessAgent` workspace for local files (offloaded messages, memory), default `~/.yoke/workspace`; it stays on the machine and is not shared between replicas |

The agent is built without file, shell or web tools, and sub-agents are disabled as a whole: reading the 2.0.4 source shows that a sub-agent inherits only the file and shell switches, not the web switch, so leaving sub-agents on would let the model spawn one that can fetch arbitrary URLs. What remains is limited to the memory and session tools (`memory_save` only appends to the fixed `MEMORY.md` and `memory/<date>.md` in the workspace, without a size limit), `load_skill_through_path` and `wait_async_results`. A `MiddlewareBase` bean is attached to every new agent; `LoggingMiddleware` logs agent, model and tool timings without tool arguments or outputs. OpenTelemetry tracing is not attached by default: add an OpenTelemetry SDK and exporter and register an `OtelTracingMiddleware` bean when you need it.

To use another model, replace `agentscope-openai-spring-boot-starter` in `build.gradle` with the matching starter (dashscope, anthropic, gemini or ollama) and change `agentscope.model.provider`.

## Project Structure

```
src/main/java/com/yoke/
├── YokeApplication.java        Application entry point
├── api/                        HTTP contract (ChatApi: paths, validation and docs live here)
├── codec/                      Conversion between domain types and framework types (MessageCodec, EventCodec)
├── config/                     Agent and Redis configuration (HarnessAgentFactory, YokeProperties)
├── controller/                 Contract implementation (ChatController)
├── middleware/                 Agent middlewares (LoggingMiddleware)
└── model/                      Request, response and stream event objects (ChatRequest, ChatResponse, ChatEvent)
src/main/resources/             application.yml and the per-profile configuration
docker-compose.yml              Local Redis, also used by the integration tests
docs/brand/                     Logo
```

## Learn More

- [AgentScope Java documentation](https://java.agentscope.io): the full guide to agents, tools, permissions, state stores and more.

## Contributing

Issues and pull requests are welcome. AgentScope message and event types must not leak out of `codec` (and `middleware`, whose hook signatures need them); `ArchitectureTests` enforces this. Before submitting, run `./gradlew build` and make sure all tests pass. Code comments are written in Chinese.

## License

Yoke is open-sourced software licensed under the [Apache License 2.0](LICENSE).

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=sinabs/spring-agent-scope&type=Date)](https://star-history.com/#sinabs/spring-agent-scope&Date)
