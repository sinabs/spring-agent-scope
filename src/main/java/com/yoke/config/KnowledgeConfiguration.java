package com.yoke.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import com.yoke.knowledge.EmbeddingClient;
import com.yoke.knowledge.retrieve.HybridRetrievePort;
import com.yoke.knowledge.retrieve.KeywordRetrievePort;
import com.yoke.knowledge.KnowledgeCorpus;
import com.yoke.knowledge.embed.ArkEmbeddingClient;
import com.yoke.knowledge.embed.OpenAiEmbeddingClient;
import com.yoke.model.Passage;
import com.yoke.knowledge.RetrievePort;
import com.yoke.knowledge.retrieve.VectorRetrievePort;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 知识库检索的装配：语料来自 {@code classpath:knowledge/*.md}。
 *
 * <p>检索链路分层，由里到外：
 * <ol>
 *   <li>关键词检索（{@link KeywordRetrievePort}）永远可用，是兜底；</li>
 *   <li>配了向量化服务（{@code yoke.knowledge.embedding}）时，再加一路向量检索，
 *       两路用 RRF 融合（{@link HybridRetrievePort}），任何一路失败退回另一路。</li>
 * </ol>
 * 所以没有 embedding 密钥时应用照常启动，检索只是退化成纯关键词。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class KnowledgeConfiguration {

    static final String CORPUS_PATTERN = "classpath*:knowledge/*.md";

    @Bean
    public RetrievePort retrievePort(YokeProperties yoke) {
        List<Passage> passages = KnowledgeCorpus.loadFromClasspath(CORPUS_PATTERN);
        log.info("知识库已加载 {} 个片段", passages.size());
        KeywordRetrievePort keyword = new KeywordRetrievePort(passages);
        YokeProperties.Knowledge.Embedding embedding = yoke.knowledge().embedding();
        if (!embedding.enabled()) {
            log.info("未配置 yoke.knowledge.embedding，检索只使用关键词");
            return keyword;
        }
        // 连接和读取都设超时：向量化服务挂了要快速失败，让上层降级到关键词，而不是拖住整个工具调用
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        RestClient client = RestClient.builder()
                .baseUrl(embedding.baseUrl())
                .defaultHeader("Authorization", "Bearer " + embedding.apiKey())
                .requestFactory(requestFactory)
                .build();
        EmbeddingClient embeddings = switch (embedding.protocol()) {
            case ARK_MULTIMODAL -> new ArkEmbeddingClient(client, embedding.modelName(), embedding.dimensions());
            case OPENAI -> new OpenAiEmbeddingClient(client, embedding.modelName(), embedding.dimensions());
        };
        log.info("检索使用向量加关键词双路融合，向量模型：{}，协议：{}", embedding.modelName(), embedding.protocol());
        return new HybridRetrievePort(List.of(new VectorRetrievePort(passages, embeddings), keyword));
    }
}
