package com.yoke.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import com.yoke.knowledge.HybridRetrievePort;
import com.yoke.knowledge.KeywordRetrievePort;
import com.yoke.knowledge.Passage;
import com.yoke.knowledge.RetrievePort;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeConfigurationTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(YokeProperties.class)
    @Import(KnowledgeConfiguration.class)
    static class Context {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Context.class)
            .withPropertyValues("yoke.redis.uri=redis://127.0.0.1:6379", "yoke.workspace=/tmp/yoke");

    @Test
    void 没有配置向量化服务时只用关键词检索且应用照常启动() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RetrievePort port = context.getBean(RetrievePort.class);
            assertThat(port).isInstanceOf(KeywordRetrievePort.class);
            assertThat(port.retrieve("对话历史存在哪里", 3).block()).isNotEmpty();
        });
    }

    @Test
    void 只配了模型名没有密钥时同样不启用向量检索() {
        runner.withPropertyValues("yoke.knowledge.embedding.model-name=text-embedding-3-small")
                .run(context -> assertThat(context.getBean(RetrievePort.class)).isInstanceOf(KeywordRetrievePort.class));
    }

    @Test
    void 配了向量化服务时使用双路融合() {
        runner.withPropertyValues("yoke.knowledge.embedding.model-name=text-embedding-3-small",
                        "yoke.knowledge.embedding.api-key=sk-test")
                .run(context -> assertThat(context.getBean(RetrievePort.class)).isInstanceOf(HybridRetrievePort.class));
    }

    @Test
    void 向量化服务不可用时检索自动退回关键词() throws IOException {
        // 本地起一个永远返回 500 的向量化服务：向量路失败，关键词路必须兜住
        HttpServer broken = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broken.createContext("/", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        broken.start();
        try {
            runner.withPropertyValues("yoke.knowledge.embedding.model-name=text-embedding-3-small",
                            "yoke.knowledge.embedding.api-key=sk-test",
                            "yoke.knowledge.embedding.base-url=http://127.0.0.1:" + broken.getAddress().getPort() + "/v1")
                    .run(context -> {
                        RetrievePort port = context.getBean(RetrievePort.class);
                        assertThat(port).isInstanceOf(HybridRetrievePort.class);
                        assertThat(port.retrieve("对话历史存在哪里", 3).block()).extracting(Passage::text)
                                .anySatisfy(text -> assertThat(text).contains("Redis"));
                    });
        } finally {
            broken.stop(0);
        }
    }
}
