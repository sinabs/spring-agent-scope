package com.yoke.knowledge.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import cn.hutool.core.util.StrUtil;
import com.yoke.knowledge.EmbeddingClient;
import com.yoke.model.Passage;
import com.yoke.knowledge.RetrievePort;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 向量检索：片段向量放在内存里，查询时用余弦相似度取前 K 个。
 *
 * <p>片段向量在第一次检索时才算（懒索引），启动不依赖向量化服务；索引失败时这次检索报错，
 * 下一次检索会重试，不会把失败结果缓存下来。索引成功后只读，线程安全。
 */
@Slf4j
public final class VectorRetrievePort implements RetrievePort {

    /** 一次向量化请求最多带多少个片段。 */
    static final int BATCH_SIZE = 16;

    /**
     * 默认最低相似度：低于它的片段视为不相关，不返回。问和知识库无关的问题时应当检索不到，
     * 而不是凑够 K 条无关资料让模型去引用。不同向量模型的分数分布不同，换模型时按需调整。
     */
    static final double DEFAULT_MIN_SCORE = 0.3;

    private final List<Passage> passages;
    private final EmbeddingClient embeddings;
    private final double minScore;

    private Mono<List<double[]>> index;

    public VectorRetrievePort(List<Passage> passages, EmbeddingClient embeddings) {
        this(passages, embeddings, DEFAULT_MIN_SCORE);
    }

    public VectorRetrievePort(List<Passage> passages, EmbeddingClient embeddings, double minScore) {
        this.passages = List.copyOf(passages);
        this.embeddings = embeddings;
        this.minScore = minScore;
    }

    @Override
    public Mono<List<Passage>> retrieve(String query, int topK) {
        if (StrUtil.isBlank(query) || topK <= 0 || passages.isEmpty()) {
            return Mono.just(List.of());
        }
        return index().flatMap(vectors -> embeddings.embed(List.of(query)).map(queryVectors -> {
            double[] queryVector = queryVectors.getFirst();
            record Scored(Passage passage, double score) {
            }
            List<Scored> scored = new ArrayList<>();
            for (int i = 0; i < passages.size(); i++) {
                scored.add(new Scored(passages.get(i), cosine(queryVector, vectors.get(i))));
            }
            return scored.stream()
                    .filter(item -> item.score() >= minScore)
                    .sorted(Comparator.comparingDouble(Scored::score).reversed())
                    .limit(topK)
                    .map(Scored::passage)
                    .toList();
        }));
    }

    /** 同一时刻只会有一个在建的索引；失败后清掉，让下一次检索重试。 */
    private synchronized Mono<List<double[]>> index() {
        if (Objects.isNull(index)) {
            index = buildIndex()
                    .doOnError(e -> {
                        log.warn("知识库向量索引构建失败，下次检索会重试：{}", e.getMessage());
                        resetIndex();
                    })
                    .cache();
        }
        return index;
    }

    private synchronized void resetIndex() {
        index = null;
    }

    private Mono<List<double[]>> buildIndex() {
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < passages.size(); from += BATCH_SIZE) {
            batches.add(passages.subList(from, Math.min(from + BATCH_SIZE, passages.size())).stream()
                    .map(Passage::text).toList());
        }
        return Flux.fromIterable(batches)
                .concatMap(embeddings::embed)
                .collectList()
                .map(lists -> lists.stream().flatMap(List::stream).toList())
                .filter(vectors -> vectors.size() == passages.size())
                .switchIfEmpty(Mono.error(new IllegalStateException("向量化结果的数量与片段数量不一致")));
    }

    static double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalStateException("向量维度不一致：" + a.length + " 与 " + b.length);
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
