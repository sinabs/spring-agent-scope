package com.yoke.knowledge;

import java.util.List;

import reactor.core.publisher.Mono;

/** 文本向量化：一批文本进，同样顺序、同样数量的向量出。 */
public interface EmbeddingClient {

    Mono<List<double[]>> embed(List<String> texts);
}
