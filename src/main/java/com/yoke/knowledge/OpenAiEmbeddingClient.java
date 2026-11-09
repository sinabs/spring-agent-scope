package com.yoke.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 调用 OpenAI 兼容的 {@code POST {baseUrl}/embeddings} 接口。
 *
 * <p>为什么自己写而不用框架的 {@code OpenAITextEmbedding}：它在 rag-simple 模块里。2.0.4 里 agentscope-core 的
 * {@code core/rag} 包（{@code Knowledge}、{@code Document}、{@code RetrieveConfig}）已整体标记为
 * {@code @Deprecated(forRemoval = true, since = "2.0.0")}；rag-simple 的 pom 还把 Qdrant client、Milvus sdk、
 * elasticsearch-java、postgresql、pgvector、pdfbox、poi-ooxml、tika-core、tika-parsers-standard-package、
 * dashscope-sdk-java、openai-java、httpclient5、commons-lang3、model-dashscope、model-ollama 都作为普通依赖
 * （无 scope、非 optional）带进来；而这个接口只是一个 JSON 请求。
 *
 * <p>隐私：用户的检索问题和知识库语料会发送给配置的 embedding 服务提供方。
 * RestClient 是阻塞的，所以放到 boundedElastic 上执行，不占用响应式线程。
 */
public final class OpenAiEmbeddingClient implements EmbeddingClient {

    private final RestClient client;
    private final String modelName;
    private final Integer dimensions;

    public OpenAiEmbeddingClient(RestClient client, String modelName, Integer dimensions) {
        this.client = client;
        this.modelName = modelName;
        this.dimensions = dimensions;
    }

    @Override
    public Mono<List<double[]>> embed(List<String> texts) {
        return Mono.fromCallable(() -> request(texts)).subscribeOn(Schedulers.boundedElastic());
    }

    @SuppressWarnings("unchecked")
    private List<double[]> request(List<String> texts) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("input", texts);
        body.put("encoding_format", "float");
        if (Objects.nonNull(dimensions)) {
            body.put("dimensions", dimensions);
        }
        Map<String, Object> response = client.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (Objects.isNull(response) || !(response.get("data") instanceof List<?> data) || data.size() != texts.size()) {
            throw new IllegalStateException("向量化接口返回的条数与请求不一致");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        data.forEach(item -> items.add((Map<String, Object>) item));
        // 接口按 index 标识顺序，不假定返回顺序一定和请求一致
        items.sort(Comparator.comparing(item -> ((Number) item.get("index")).intValue()));
        List<double[]> vectors = new ArrayList<>();
        for (Map<String, Object> item : items) {
            List<Number> values = (List<Number>) item.get("embedding");
            vectors.add(values.stream().mapToDouble(Number::doubleValue).toArray());
        }
        return vectors;
    }
}
