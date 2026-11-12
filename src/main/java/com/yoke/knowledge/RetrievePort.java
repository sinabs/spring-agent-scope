package com.yoke.knowledge;

import java.util.List;

import com.yoke.model.Passage;
import reactor.core.publisher.Mono;

/**
 * 检索端口：给一个问题，返回最相关的若干片段。
 *
 * <p>这是 Yoke 自己的接口，不依赖 AgentScope：AgentScope 里框架的 rag 包已经整体标记为「将移除」
 * （{@code @Deprecated(forRemoval = true)}，注释写明「在应用层集成检索」），所以检索放在应用层，
 * 以后换向量库、换检索算法都不碰 Agent 和工具的代码。
 *
 * <p>约定：结果按相关度从高到低排列，最多 {@code topK} 条，没有命中返回空列表；
 * 实现失败时通过 Mono 的错误信号表达，由调用方决定怎么降级。
 */
public interface RetrievePort {

    Mono<List<Passage>> retrieve(String query, int topK);
}
