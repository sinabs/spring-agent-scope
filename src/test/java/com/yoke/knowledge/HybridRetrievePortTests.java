package com.yoke.knowledge;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HybridRetrievePortTests {

    private static Passage p(String id) {
        return new Passage(id, "文档", "正文 " + id);
    }

    private static RetrievePort returning(Passage... passages) {
        return (query, topK) -> Mono.just(List.of(passages));
    }

    private static RetrievePort failing() {
        return (query, topK) -> Mono.error(new IllegalStateException("向量化服务超时"));
    }

    @Test
    void RRF按名次融合_两路都靠前的片段排第一() {
        // 向量路：A B C；关键词路：B D A。B 在两路分别是第 2 和第 1 名，A 是第 1 和第 3 名
        RetrievePort hybrid = new HybridRetrievePort(List.of(returning(p("A"), p("B"), p("C")), returning(p("B"), p("D"), p("A"))));

        List<Passage> result = hybrid.retrieve("q", 4).block();

        // B: 1/62 + 1/61；A: 1/61 + 1/63；C: 1/63；D: 1/62
        assertThat(result).extracting(Passage::id).containsExactly("B", "A", "D", "C");
    }

    @Test
    void RRF得分使用常数60() {
        assertThat(HybridRetrievePort.RRF_K).isEqualTo(60);
        // 第 1 名对第 2 名的优势是 1/61 - 1/62；只有一路时直接保持原顺序
        assertThat(HybridRetrievePort.fuse(List.of(List.of(p("X"), p("Y"))), 2)).extracting(Passage::id).containsExactly("X", "Y");
    }

    @Test
    void 融合后只保留topK条() {
        RetrievePort hybrid = new HybridRetrievePort(List.of(returning(p("A"), p("B")), returning(p("C"), p("D"))));

        assertThat(hybrid.retrieve("q", 3).block()).hasSize(3);
    }

    @Test
    void 同一个片段在两路里重复出现只算一条() {
        RetrievePort hybrid = new HybridRetrievePort(List.of(returning(p("A")), returning(p("A"))));

        assertThat(hybrid.retrieve("q", 5).block()).extracting(Passage::id).containsExactly("A");
    }

    @Test
    void 一路失败时退回另一路() {
        RetrievePort hybrid = new HybridRetrievePort(List.of(failing(), returning(p("K1"), p("K2"))));

        assertThat(hybrid.retrieve("q", 5).block()).extracting(Passage::id).containsExactly("K1", "K2");
    }

    @Test
    void 关键词路失败时退回向量路() {
        RetrievePort hybrid = new HybridRetrievePort(List.of(returning(p("V1")), failing()));

        assertThat(hybrid.retrieve("q", 5).block()).extracting(Passage::id).containsExactly("V1");
    }

    @Test
    void 一路为空另一路有结果时返回有结果的那一路() {
        RetrievePort hybrid = new HybridRetrievePort(List.of(returning(), returning(p("K1"))));

        assertThat(hybrid.retrieve("q", 5).block()).extracting(Passage::id).containsExactly("K1");
    }

    @Test
    void 两路都成功但都为空时返回空列表() {
        RetrievePort hybrid = new HybridRetrievePort(List.of(returning(), returning()));

        assertThat(hybrid.retrieve("q", 5).block()).isEmpty();
    }

    @Test
    void 所有路都失败时抛出错误而不是假装没有命中() {
        RetrievePort hybrid = new HybridRetrievePort(List.of(failing(), failing()));

        assertThatThrownBy(() -> hybrid.retrieve("q", 5).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("超时");
    }

    @Test
    void 失败的一路不会拖累成功的一路被调用() {
        AtomicInteger calls = new AtomicInteger();
        RetrievePort counting = (query, topK) -> {
            calls.incrementAndGet();
            return Mono.just(List.of(p("K")));
        };

        new HybridRetrievePort(List.of(failing(), counting)).retrieve("q", 3).block();

        assertThat(calls).hasValue(1);
    }

    @Test
    void 每一路取的候选比最终topK更宽() {
        AtomicInteger requested = new AtomicInteger();
        RetrievePort spy = (query, topK) -> {
            requested.set(topK);
            return Mono.just(List.of());
        };

        new HybridRetrievePort(List.of(spy)).retrieve("q", 3).block();

        assertThat(requested.get()).isGreaterThan(3);
    }

    @Test
    void 没有任何检索通道时构造即失败() {
        assertThatThrownBy(() -> new HybridRetrievePort(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
