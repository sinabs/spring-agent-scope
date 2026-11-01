package com.yoke.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorRetrievePortTests {

    /** 假的向量化：文本里出现哪个关键字就在对应维度上打 1，足够让余弦相似度可预测。 */
    static class KeywordEmbedding implements EmbeddingClient {
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> batchSizes = new ArrayList<>();
        boolean failNext;

        @Override
        public synchronized Mono<List<double[]>> embed(List<String> texts) {
            calls.incrementAndGet();
            batchSizes.add(texts.size());
            if (failNext) {
                failNext = false;
                return Mono.error(new IllegalStateException("上游 503"));
            }
            return Mono.just(texts.stream().map(text -> new double[] {
                    text.contains("Redis") ? 1 : 0, text.contains("工具") ? 1 : 0, text.contains("线程") ? 1 : 0, 0.01}).toList());
        }
    }

    private static final List<Passage> PASSAGES = List.of(
            new Passage("a", "存储", "历史放在 Redis"),
            new Passage("b", "工具", "calculate 工具做运算"),
            new Passage("c", "线程", "使用虚拟线程"));

    @Test
    void 按余弦相似度返回最接近的片段() {
        VectorRetrievePort port = new VectorRetrievePort(PASSAGES, new KeywordEmbedding());

        assertThat(port.retrieve("Redis 在哪", 1).block()).extracting(Passage::id).containsExactly("a");
        assertThat(port.retrieve("哪个工具", 2).block()).extracting(Passage::id).first().isEqualTo("b");
    }

    @Test
    void 片段向量只算一次_之后每次检索只多一次查询向量化() {
        KeywordEmbedding embedding = new KeywordEmbedding();
        VectorRetrievePort port = new VectorRetrievePort(PASSAGES, embedding);

        port.retrieve("Redis", 1).block();
        port.retrieve("工具", 1).block();
        port.retrieve("线程", 1).block();

        // 1 次建索引（3 个片段一批）+ 3 次查询
        assertThat(embedding.calls).hasValue(4);
        assertThat(embedding.batchSizes.getFirst()).isEqualTo(3);
    }

    @Test
    void 片段多时分批向量化() {
        List<Passage> many = new ArrayList<>();
        for (int i = 0; i < VectorRetrievePort.BATCH_SIZE + 4; i++) {
            many.add(new Passage("p" + i, "文档", "Redis " + i));
        }
        KeywordEmbedding embedding = new KeywordEmbedding();

        new VectorRetrievePort(many, embedding).retrieve("Redis", 1).block();

        assertThat(embedding.batchSizes.subList(0, 2)).containsExactly(VectorRetrievePort.BATCH_SIZE, 4);
    }

    @Test
    void 建索引失败时报错且下一次检索会重试而不是缓存失败() {
        KeywordEmbedding embedding = new KeywordEmbedding();
        embedding.failNext = true;
        VectorRetrievePort port = new VectorRetrievePort(PASSAGES, embedding);

        assertThatThrownBy(() -> port.retrieve("Redis", 1).block()).hasMessageContaining("503");

        assertThat(port.retrieve("Redis", 1).block()).extracting(Passage::id).containsExactly("a");
    }

    @Test
    void 向量化返回条数不对时报错() {
        EmbeddingClient broken = texts -> Mono.just(List.of(new double[] {1}));
        VectorRetrievePort port = new VectorRetrievePort(PASSAGES, broken);

        assertThatThrownBy(() -> port.retrieve("Redis", 1).block()).hasMessageContaining("不一致");
    }

    @Test
    void 空问题和空语料不触发任何向量化调用() {
        KeywordEmbedding embedding = new KeywordEmbedding();

        assertThat(new VectorRetrievePort(PASSAGES, embedding).retrieve(" ", 3).block()).isEmpty();
        assertThat(new VectorRetrievePort(List.of(), embedding).retrieve("Redis", 3).block()).isEmpty();
        assertThat(embedding.calls).hasValue(0);
    }

    @Test
    void 余弦相似度对零向量安全() {
        assertThat(VectorRetrievePort.cosine(new double[] {0, 0}, new double[] {1, 1})).isZero();
        assertThat(VectorRetrievePort.cosine(new double[] {1, 0}, new double[] {1, 0})).isEqualTo(1.0);
        assertThatThrownBy(() -> VectorRetrievePort.cosine(new double[] {1}, new double[] {1, 2}))
                .hasMessageContaining("维度");
    }
}
