package com.yoke.api;

import com.yoke.model.ChatRequest;
import com.yoke.model.ChatResponse;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 与 Agent 对话的 HTTP 接口契约。
 *
 * <p>每次请求都会新建一个临时 HarnessAgent，用完即关闭；对话历史保存在 Redis 里，
 * 按 {@code (userId, sessionId)} 隔离，所以新建的 Agent 仍能接上同一会话之前的对话，服务重启后历史也还在。
 * 路径和参数注解都声明在这里，实现类只负责业务逻辑。
 *
 * <p>会话规则：{@code userId} 和 {@code sessionId} 必须成对非空才对应一个会话槽位，服务端保证二者到达 Agent 时都有值：
 * <ul>
 *   <li>两个都传：用这一对；</li>
 *   <li>只传 {@code sessionId}：归属 {@code default} 用户，与旧版客户端的行为一致；</li>
 *   <li>只传 {@code userId}：为该用户新建一个会话，新的会话 ID 在响应里返回；</li>
 *   <li>都不传：{@code default} 用户下新建会话。</li>
 * </ul>
 * 不能把「只给其一」当成无会话直接交给框架：AgentScope 在缺 {@code sessionId} 时会落到以 Agent 名称命名的共享会话，
 * 缺 {@code userId} 会落到匿名用户，所有调用方的历史都会混在一起。
 *
 * <p>标识格式：{@code userId} 和 {@code sessionId} 只能包含字母、数字、下划线、点和短横线，长度 1 到 64，
 * 且不能全是点（排除 {@code .}、{@code ..}），不合法返回 400；缺失或空白不算不合法（分别用 {@code default} 和新建会话）。
 * 这样限制是因为框架把槽位拼成 {@code userId/sessionId}，允许斜杠会让不同的一对撞到同一个槽位，
 * 而 userId 还会成为工作区的目录名。
 *
 * <p>实现说明：暂时没有鉴权，{@code userId} 由调用方自报，不可信，不要把它当成身份校验；
 * 同一个会话的并发请求不会被串行化，由调用方保证同一会话同一时间只发一个请求。
 *
 * <p>模型可以使用的工具（精确清单由 ToolPermissionTests 守着）：
 * <ul>
 *   <li>Yoke 自带：{@code get_current_time}（带时区偏移的当前时间）、{@code calculate}（四则运算，手写解析器，不执行任何代码）、
 *       {@code list_my_notes}（当前用户的笔记，演示数据）、{@code retrieve}（在 Yoke 自己的知识库里检索）；</li>
 *   <li>框架内置：{@code memory_save}、{@code load_skill_through_path}、{@code wait_async_results}，
 *       以及子 Agent 和后台任务相关的 {@code agent_spawn}、{@code agent_send}、{@code agent_list}、
 *       {@code task_output}、{@code task_cancel}、{@code task_list}；</li>
 *   <li>配置了 {@code yoke.mcp.servers} 时，再加上 MCP 服务公布的工具，工具名是服务端公布的原名，不加前缀。</li>
 * </ul>
 * 不开放文件读写、命令执行和联网（{@code web_fetch}、{@code web_search}）：主 Agent 同时用 {@code disableWebTools} 和工具配置的 deny 名单，子 Agent 只靠 deny 名单（它们不继承 {@code disableWebTools}）。
 * 读类的记忆和会话工具（{@code memory_get}、{@code memory_search}、{@code session_history}、{@code session_list}、
 * {@code session_search}）也被 deny 名单拒绝：{@code memory_get} 只校验路径不越出工作区根目录、不校验是否在当前用户目录下，
 * {@code session_history} 把模型给的 ID 直接拼进路径，开着就能让模型读到别的用户的记忆和会话。
 * 工具配置是显式传入的，工作区里的 {@code tools.json} 不生效。其中 {@code memory_save} 是唯一写盘的，
 * 只往工作区里固定的 {@code MEMORY.md} 和 {@code memory/<日期>.md} 追加，路径不由模型控制，但追加的内容没有大小限制。
 *
 * <p>Agent 在结果交给调用方之后才关闭，关闭时抛出的异常只记日志：不会把已经成功的对话变成 500，
 * 流式接口也不会在 {@code RUN_FINISHED} 之后再追加 {@code RUN_ERROR}。
 *
 * <p>业务上下文不经过模型：{@code list_my_notes} 要知道「当前用户是谁」，userId 由控制器放进 RuntimeContext，
 * 工具按类型取用，不是模型传的参数，模型看不到也伪造不了。这个 userId 本身仍是调用方自报的（见上面的实现说明）。
 *
 * <p>引用格式：{@code retrieve} 返回的资料带 {@code [文档名.md#片段编号]} 这样的编号和来源文档标题，编号是片段的稳定标识
 * （文档名加正文摘要，正文改了编号也跟着变），同一轮对话里多次检索也不会重复；模型回答时原样照抄编号标注出处，调用方可以据此回溯。检索是工具化的，只有模型认为需要时才会调用，
 * 没有同时开启框架的 knowledge 自动注入。没有配置向量化服务时只用关键词检索，向量化服务出错时自动退回关键词检索。
 *
 * <p>子 Agent：{@code summarizer}（代码里声明，只做摘要，没有任何业务工具）、{@code kb-researcher}
 * （工作区文件 {@code workspace/subagents/kb-researcher.md} 声明，只能用 {@code retrieve}）和框架内置的
 * {@code general-purpose}（继承 Yoke 的业务工具）。AgentScope 里子 Agent 不继承父 Agent 的联网工具关闭，所以联网工具靠工具配置里的
 * deny 名单在所有层（主 Agent、内置子 Agent、声明的子 Agent）一并禁掉；子 Agent 同样没有文件读写和命令执行，也不能再派生子 Agent。
 *
 * <p>MCP 工具的授权：AgentScope 对 MCP 工具只放行服务端声明了 {@code readOnlyHint} 的，其余需要用户确认，
 * 而本接口没有人工确认通道，否则一轮对话会在工具调用处无声结束、调用方拿到空回复。
 * 所以注册时就只放行声明了只读的工具，其余跳过（启动日志里记工具名），模型根本看不到它们，
 * 这对主 Agent 和子 Agent 都成立。MCP 服务配置的服务连不上时，应用启动失败。
 *
 * <p>隐私：配置了向量化服务（{@code yoke.knowledge.embedding}）后，用户的检索问题和知识库语料会发送给该服务的提供方。
 *
 * <p>已知限制：{@code HarnessAgent.close()} 会等待框架里全局的异步写入静默
 * （{@code awaitMirrorQuiescence} 和 {@code awaitQuiescence}，各最长 5 秒），它在每个请求的清理回调里执行，
 * 因此是跨请求耦合的：别的请求的在途写入会让这次关闭变慢。目前没有把关闭挪到专用线程，也没做并发压测。
 */
@RequestMapping("/api/chat")
public interface ChatApi {

    /**
     * 向 Agent 发送一条消息并返回回复。
     *
     * <p>同一个 {@code (userId, sessionId)} 共享对话历史；不传 {@code sessionId} 时由服务端新建会话，
     * 并在响应里返回新的会话 ID，后续请求带上它即可延续对话；不传 {@code userId} 时使用 {@code default}。
     * 完整规则见类说明。
     *
     * <p>{@code message} 为必填：缺失、为 null、空串或纯空白时都由参数校验拦截，返回 400。
     * {@code sessionId} 为空白时视同未传；传了就要符合 {@link com.yoke.model.SafeId} 的规则
     * （字母、数字、{@code _.-}，1~64 位，不能全是点），否则返回 400。规则与 {@link #stream} 的 {@code threadId} 相同。
     *
     * @param request 对话请求，包含可选的用户标识、可选的会话 ID 和必填的用户输入
     * @return Agent 的回复，以及本次对话所属的会话 ID
     */
    @PostMapping
    Mono<ChatResponse> chat(@Valid @RequestBody ChatRequest request);

    /**
     * 以 AG-UI 协议向 Agent 发送一条消息，用 SSE（{@code text/event-stream}）边推理边推送 AG-UI 事件。
     *
     * <p>请求体是标准的 AG-UI {@code RunAgentInput} JSON，{@code Content-Type} 必须是 {@code application/json}
     * （否则返回 415，也避免跨站页面用不触发预检的 {@code text/plain} 请求发起对话）。这里以字符串接收，由实现解析和校验，
     * 所以契约里不出现框架类型。校验失败（请求体不是合法 JSON、{@code messages} 为空、最后一条不是 user 消息、
     * 最后一条 user 消息没有非空白的文本、{@code threadId} 缺失或不合规）都在建立流之前返回 400。
     * {@code content} 目前只支持字符串，内容块形式（包括全是文本块、带图片等）返回 400。
     *
     * <p>{@code threadId} 就是会话 ID，同一个 {@code threadId} 共享对话历史，也与 {@link #chat} 的
     * {@code sessionId} 共用同一份历史。AG-UI 要求客户端自己生成 {@code threadId}，所以这里必填，
     * 取值限制为字母、数字、{@code _.-}，1~64 位，不能全是点，避免被用来拼出别的会话的存储位置。
     * {@code runId} 必填（RunAgentInput 要求非空），缺失或空白返回 400。
     * AG-UI 的请求体没有用户标识，流式接口固定归属 {@code default} 用户（与 {@link #chat} 不传 {@code userId} 时一致）。
     *
     * <p>对话历史保存在服务端的状态存储里，服务端只采用 {@code messages} 的<b>最后一条 user 消息</b>，
     * 前面的消息、{@code tools}、{@code context}、{@code state}、{@code forwardedProps}、{@code resume}
     * 都会被忽略，客户端不能借此给 Agent 增加工具或改写历史。这条 user 消息也只取文本：它的 {@code id}、
     * {@code toolCalls}、{@code toolCallId} 不会进入历史，消息 ID 由服务端生成。
     *
     * <p>SSE 只发 {@code data:}，不设 {@code event:} 名，事件类型在 JSON 的 {@code type} 字段里。
     * 一次对话可能依次出现：
     * <ul>
     *   <li>{@code RUN_STARTED}：第一个事件，带 {@code threadId} 和 {@code runId}；</li>
     *   <li>{@code TEXT_MESSAGE_START}、{@code TEXT_MESSAGE_CONTENT}（{@code delta} 是文本增量）、
     *       {@code TEXT_MESSAGE_END}：模型生成的文本，按顺序拼接 {@code delta} 即得到回复；</li>
     *   <li>{@code TOOL_CALL_START}、{@code TOOL_CALL_ARGS}、{@code TOOL_CALL_END}、
     *       {@code TOOL_CALL_RESULT}：工具调用及其结果，靠相同的 {@code toolCallId} 对应；
     *       {@code TOOL_CALL_ARGS} 的 {@code delta} 是入参 JSON 的片段，只有模型分片返回入参时才会出现，
     *       不要依赖它一定存在；</li>
     *   <li>{@code CUSTOM}，{@code name} 为 {@code token_usage}：每次模型调用结束后推送一次用量，
     *       {@code value} 里有本次增量和累计值；</li>
     *   <li>{@code CUSTOM}，{@code name} 为 {@code hint}：框架在推理过程中追加的提示，
     *       {@code value} 里是 {@code source} 和 {@code text}；</li>
     *   <li>{@code RUN_FINISHED}：正常结束；</li>
     *   <li>{@code RUN_ERROR}：推理过程中出错。</li>
     * </ul>
     * 没有 {@code done} 事件，也不会重复最终全文：完整回复由客户端拼接 {@code TEXT_MESSAGE_CONTENT} 得到。
     *
     * <p>一次对话以 {@code RUN_FINISHED} 或 {@code RUN_ERROR} 结尾，二者互斥，只会出现一个。
     * 这个承诺依赖适配器的行为（出错时默认不补 {@code RUN_FINISHED}）和 AgentScope 框架在流结束时总会发出结束事件，
     * 实现里没有额外的兜底。{@code RUN_ERROR} 的 {@code message} 是原样的异常信息，未做脱敏，
     * 调用方不要直接展示给终端用户。
     *
     * <p>错误通过 {@code RUN_ERROR} 事件体现，HTTP 状态码仍是 200 并正常结束流，而不是中途断开连接，
     * 所以调用方只需要根据最后一个事件判断成败。
     *
     * <p>每次请求新建一个临时 Agent：流正常结束、出错或被客户端取消（断开连接）时都会关闭它。
     *
     * <p>已知限制：与 {@link #chat} 一样，同一个会话的并发请求不会被串行化，
     * 由调用方保证同一会话同一时间只发一个请求；取消时已经产生的部分对话是否写入历史，取决于框架的保存时机；
     * 运行超时未验证：AgentScope 的适配器配置里有 {@code runTimeout}，但源码里没有任何地方读取它。
     *
     * @param body AG-UI {@code RunAgentInput} 的 JSON 文本
     * @return 事件流，每个元素的 data 是一个 AG-UI 事件的 JSON，最后一个事件是 {@code RUN_FINISHED} 或 {@code RUN_ERROR}
     */
    @PostMapping(path = "/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> stream(@RequestBody String body);
}
