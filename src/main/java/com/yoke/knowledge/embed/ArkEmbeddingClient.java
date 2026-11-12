package com.yoke.knowledge.embed;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.yoke.knowledge.EmbeddingClient;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 火山方舟多模态向量模型（例如 doubao-embedding-vision）的客户端，调用 {@code POST {baseUrl}/embeddings/multimodal}。
 *
 * <p>它和 OpenAI 兼容的 {@code /embeddings} 有三处不同，所以不能复用 {@link OpenAiEmbeddingClient}：
 * <ul>
 *   <li>路径是 {@code /embeddings/multimodal}；</li>
 *   <li>请求体的 {@code input} 是内容对象的数组（{@code {"type":"text","text":"……"}}），不是字符串数组；</li>
 *   <li>响应的 {@code data} 是单个对象（也兼容数组），没有 {@code index}；
 *       而且<b>不支持批量</b>：一次传多个 input 会被融合成一个向量，所以一个文本必须一次请求。</li>
 * </ul>
 *
 * <p>一批文本拆成逐条请求，并发度受 {@link #CONCURRENCY} 限制，结果按输入顺序返回。
 * 请求和响应格式参考 Peliplat 的 {@code ArkEmbeddingModel}；它那套退避重试和全局背压是给批量入库用的，
 * 这里语料只有几篇小文档，向量化失败直接报错，由上层降级到关键词检索，不重试。
 *
 * <p>隐私：用户的检索问题和知识库语料会发送给方舟。RestClient 是阻塞的，所以放到 boundedElastic 上执行。
 */
@RequiredArgsConstructor
public final class ArkEmbeddingClient implements EmbeddingClient {

    /** 同时在途的请求数：不支持批量，片段多时不能一次把方舟打满。 */
    static final int CONCURRENCY = 4;

    private final RestClient client;
    private final String modelName;
    private final Integer dimensions;

    @Override
    public Mono<List<double[]>> embed(List<String> texts) {
        return Flux.fromIterable(texts)
                .flatMapSequential(text -> Mono.fromCallable(() -> embedOne(text)).subscribeOn(Schedulers.boundedElastic()),
                        CONCURRENCY)
                .collectList();
    }

    @SuppressWarnings("unchecked")
    private double[] embedOne(String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("input", List.of(Map.of("type", "text", "text", text)));
        if (Objects.nonNull(dimensions)) {
            body.put("dimensions", dimensions);
        }
        Map<String, Object> response = client.post()
                .uri("/embeddings/multimodal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        Object data = Objects.isNull(response) ? null : response.get("data");
        // 实测 data 是单个对象；兼容它改成数组的情况
        Object item = data instanceof List<?> list && !list.isEmpty() ? list.getFirst() : data;
        if (!(item instanceof Map<?, ?> map) || !(map.get("embedding") instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalStateException("向量化接口的响应里没有 embedding");
        }
        double[] vector = ((List<Number>) values).stream().mapToDouble(Number::doubleValue).toArray();
        if (Objects.nonNull(dimensions) && vector.length != dimensions) {
            throw new IllegalStateException("向量化接口返回的维度 " + vector.length + " 与配置的 " + dimensions + " 不一致");
        }
        return vector;
    }
}
