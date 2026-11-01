package com.yoke.api;

import com.yoke.model.ChatEvent;
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
 * 不能把「只给其一」当成无会话直接交给框架：2.0.4 里缺 {@code sessionId} 会落到以 Agent 名称命名的共享会话，
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
 *   <li>框架内置：{@code memory_save}、{@code memory_get}、{@code memory_search}、{@code session_history}、
 *       {@code session_list}、{@code session_search}、{@code load_skill_through_path}、{@code wait_async_results}，
 *       以及子 Agent 和后台任务相关的 {@code agent_spawn}、{@code agent_send}、{@code agent_list}、
 *       {@code task_output}、{@code task_cancel}、{@code task_list}；</li>
 *   <li>配置了 {@code yoke.mcp.servers} 时，再加上 MCP 服务公布的工具，工具名是服务端公布的原名，不加前缀。</li>
 * </ul>
 * 不开放文件读写、命令执行和联网（{@code web_fetch}、{@code web_search}）：主 Agent 同时用 {@code disableWebTools} 和工具配置的 deny 名单，子 Agent 只靠 deny 名单（它们不继承 {@code disableWebTools}）。其中 {@code memory_save} 是唯一写盘的，
 * 只往工作区里固定的 {@code MEMORY.md} 和 {@code memory/<日期>.md} 追加，路径不由模型控制，但追加的内容没有大小限制。
 *
 * <p>业务上下文不经过模型：{@code list_my_notes} 要知道「当前用户是谁」，userId 由控制器放进 RuntimeContext，
 * 工具按类型取用，不是模型传的参数，模型看不到也伪造不了。这个 userId 本身仍是调用方自报的（见上面的实现说明）。
 *
 * <p>引用格式：{@code retrieve} 返回的资料带 {@code [1]}、{@code [2]} 这样的编号和来源文档标题，
 * 模型回答时按相同编号标注出处，调用方可以据此回溯。检索是工具化的，只有模型认为需要时才会调用，
 * 没有同时开启框架的 knowledge 自动注入。没有配置向量化服务时只用关键词检索，向量化服务出错时自动退回关键词检索。
 *
 * <p>子 Agent：{@code summarizer}（代码里声明，只做摘要，没有任何业务工具）、{@code kb-researcher}
 * （工作区文件 {@code workspace/subagents/kb-researcher.md} 声明，只能用 {@code retrieve}）和框架内置的
 * {@code general-purpose}（继承 Yoke 的业务工具）。2.0.4 里子 Agent 不继承父 Agent 的联网工具关闭，所以联网工具靠工具配置里的
 * deny 名单在所有层（主 Agent、内置子 Agent、声明的子 Agent）一并禁掉；子 Agent 同样没有文件读写和命令执行，也不能再派生子 Agent。
 *
 * <p>MCP 工具的授权：2.0.4 对 MCP 工具只放行服务端声明了 {@code readOnlyHint} 的，其余需要用户确认，
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
     *
     * @param request 对话请求，包含可选的用户标识、可选的会话 ID 和必填的用户输入
     * @return Agent 的回复，以及本次对话所属的会话 ID
     */
    @PostMapping
    Mono<ChatResponse> chat(@Valid @RequestBody ChatRequest request);

    /**
     * 向 Agent 发送一条消息，以 SSE（{@code text/event-stream}）边推理边推送事件。
     *
     * <p>请求体和会话规则与 {@link #chat} 相同：同一个 {@code (userId, sessionId)} 共享对话历史，
     * 不传时由服务端新建会话，新的会话 ID 出现在最后的 {@code done} 事件里；{@code message} 缺失、
     * 为 null、空串或纯空白时在建立流之前就被参数校验拦截，返回 400。
     *
     * <p>SSE 的 event 名与事件类型一一对应，data 是 JSON，一次对话可能依次出现：
     * <ul>
     *   <li>{@code textDelta}：模型生成的一段文本增量，按顺序拼接即得到回复；</li>
     *   <li>{@code toolCall}：模型决定调用的工具，名称和入参已经攒齐；</li>
     *   <li>{@code toolResult}：工具的执行结果，与 {@code toolCall} 靠相同的 {@code id} 对应；</li>
     *   <li>{@code hint}：框架在推理过程中追加的提示；</li>
     *   <li>{@code done}：正常结束，带完整回复文本、会话 ID 和累计 token 用量；</li>
     *   <li>{@code error}：推理过程中出错。</li>
     * </ul>
     * 一次对话以 {@code done} 或 {@code error} 结尾，二者必有其一且只会出现一个。
     * 这个承诺依赖 2.0.4 框架在流结束时总会发出结束事件，实现里没有额外的兜底。
     *
     * <p>{@code error} 事件会原样带出异常信息，未做脱敏，调用方不要直接展示给终端用户。
     *
     * <p>错误通过 {@code error} 事件体现，HTTP 状态码仍是 200 并正常结束流，而不是中途断开连接，
     * 所以调用方只需要根据最后一个事件判断成败。
     *
     * <p>每次请求新建一个临时 Agent：流正常结束、出错或被客户端取消（断开连接）时都会关闭它。
     *
     * <p>已知限制：与 {@link #chat} 一样，同一个会话的并发请求不会被串行化，
     * 由调用方保证同一会话同一时间只发一个请求；取消时已经产生的部分对话是否写入历史，取决于框架的保存时机。
     *
     * @param request 对话请求，包含可选的用户标识、可选的会话 ID 和必填的用户输入
     * @return 事件流，最后一个事件是 {@code done} 或 {@code error}
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<ChatEvent>> stream(@Valid @RequestBody ChatRequest request);
}
