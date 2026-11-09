package com.yoke.tool;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import cn.hutool.core.util.StrUtil;
import com.yoke.knowledge.Passage;
import com.yoke.knowledge.RetrievePort;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 「工具化检索」：把知识库检索做成一个模型自己决定何时调用的工具，而不是每轮都自动往提示词里塞资料。
 *
 * <p>只走这一条通道，<b>不同时使用 HarnessAgent / ReActAgent 的 {@code knowledge()} 自动注入</b>：
 * 两条通道同时开，同一个问题会被检索两次，两份资料挤进上下文还可能互相矛盾，引用编号也对不上；
 * 而且 AgentScope 里框架的 rag 包已标记为将移除。工具化的另一个好处是闲聊不触发检索，省 token。
 *
 * <p>返回值带 {@code [1][2]} 编号，模型回答时按编号引用，用户就能回头核对出处。
 * 无状态，线程安全；{@code port} 为空表示没有配置知识库，工具返回提示语而不是抛异常。
 */
public class RetrieveTool {

    private static final Logger log = LoggerFactory.getLogger(RetrieveTool.class);

    static final int DEFAULT_TOP_K = 3;

    /** 单次最多返回几条：模型传多大都不会超过，避免一次把上下文塞满。 */
    static final int MAX_TOP_K = 8;

    static final int MAX_QUERY_LENGTH = 200;

    private final RetrievePort port;

    public RetrieveTool(RetrievePort port) {
        this.port = port;
    }

    @Tool(name = "retrieve",
            description = "当问题涉及 Yoke 自身的设计、配置、接口或使用方法，而你不确定答案时调用，先查资料再回答；"
                    + "用户明确要求查知识库或资料时必须调用，不能没查就说知识库里没有；闲聊、通用常识和计算不要调用。返回带 [1][2] 编号的资料片段，回答时请用相同的编号标注引用来源。"
                    + "查不到时如实告诉用户，不要编造。")
    public Mono<String> retrieve(
            @ToolParam(name = "query", description = "要查的问题或关键词，用完整的短句，例如「会话历史存在哪里」") String query,
            @ToolParam(name = "top_k", required = false, description = "返回几条，默认 3，上限 8") Integer topK) {
        if (Objects.isNull(port)) {
            return Mono.just("知识库尚未配置，无法检索，请直接根据已有信息回答并说明没有查资料。");
        }
        if (StrUtil.isBlank(query)) {
            return Mono.just("检索失败：query 不能为空。");
        }
        String trimmed = query.length() > MAX_QUERY_LENGTH ? query.substring(0, MAX_QUERY_LENGTH) : query;
        int limit = Optional.ofNullable(topK).map(value -> Math.clamp(value, 1, MAX_TOP_K)).orElse(DEFAULT_TOP_K);
        return port.retrieve(trimmed, limit)
                .map(RetrieveTool::format)
                .onErrorResume(error -> {
                    // 细节只写日志，不回给模型：异常信息里可能带上游服务的地址
                    log.warn("知识库检索失败", error);
                    return Mono.just("检索服务暂时不可用，请稍后再试，或直接说明无法查证。");
                });
    }

    static String format(List<Passage> passages) {
        if (passages.isEmpty()) {
            return "没有检索到相关资料。请如实告诉用户知识库里没有查到，不要编造。";
        }
        StringBuilder out = new StringBuilder("检索到 ").append(passages.size()).append(" 条相关资料，回答时用 [编号] 标注引用：");
        for (int i = 0; i < passages.size(); i++) {
            Passage passage = passages.get(i);
            out.append("\n\n[").append(i + 1).append("] 来源：").append(passage.source()).append('\n').append(passage.text());
        }
        return out.toString();
    }
}
