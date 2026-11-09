package com.yoke.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 多路检索 + RRF（Reciprocal Rank Fusion）融合，同时承担降级。
 *
 * <p>每一路各自取一批候选，按名次融合：一个片段的得分是它在各路里 {@code 1 / (60 + 名次)} 之和，
 * 60 是 RRF 论文里的常数。只看名次不看原始分数，所以向量相似度和 BM25 这种量纲完全不同的分数也能直接融合。
 *
 * <p>降级规则：
 * <ul>
 *   <li>某一路失败（比如向量化服务超时）：记一条警告，用剩下的路继续；</li>
 *   <li>所有路都失败：把错误抛出去，让调用方区分「检索坏了」和「没有命中」；</li>
 *   <li>所有路都成功但都为空：返回空列表。</li>
 * </ul>
 * 融合只发生在成功的路之间，所以向量路挂掉时结果自然退化成纯关键词检索。
 */
public final class HybridRetrievePort implements RetrievePort {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievePort.class);

    /** RRF 常数。 */
    static final int RRF_K = 60;

    /** 每一路多取的候选倍数：融合需要比最终 topK 更宽的候选池。 */
    private static final int CANDIDATE_FACTOR = 3;

    private final List<RetrievePort> channels;

    public HybridRetrievePort(List<RetrievePort> channels) {
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("至少需要一路检索");
        }
        this.channels = List.copyOf(channels);
    }

    @Override
    public Mono<List<Passage>> retrieve(String query, int topK) {
        int candidates = Math.max(topK * CANDIDATE_FACTOR, 10);
        return Flux.fromIterable(channels)
                .flatMapSequential(channel -> channel.retrieve(query, candidates)
                        .defaultIfEmpty(List.of())
                        .map(Outcome::success)
                        .onErrorResume(error -> {
                            log.warn("一路检索失败，降级使用其余检索：{} - {}", channel.getClass().getSimpleName(), error.toString());
                            return Mono.just(Outcome.failure(error));
                        }))
                .collectList()
                .flatMap(outcomes -> {
                    List<List<Passage>> succeeded = outcomes.stream()
                            .filter(outcome -> Objects.isNull(outcome.error()))
                            .map(Outcome::passages)
                            .toList();
                    if (succeeded.isEmpty()) {
                        return Mono.error(outcomes.getFirst().error());
                    }
                    return Mono.just(fuse(succeeded, topK));
                });
    }

    static List<Passage> fuse(List<List<Passage>> rankings, int topK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Passage> byId = new LinkedHashMap<>();
        for (List<Passage> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                Passage passage = ranking.get(rank);
                byId.putIfAbsent(passage.id(), passage);
                scores.merge(passage.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }
        List<String> ids = new ArrayList<>(scores.keySet());
        // 稳定排序：得分相同时保持首次出现的先后
        ids.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
        return ids.stream().limit(topK).map(byId::get).toList();
    }

    private record Outcome(List<Passage> passages, Throwable error) {

        static Outcome success(List<Passage> passages) {
            return new Outcome(passages, null);
        }

        static Outcome failure(Throwable error) {
            return new Outcome(List.of(), error);
        }
    }
}
