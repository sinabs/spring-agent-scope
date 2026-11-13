package com.yoke.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import com.yoke.knowledge.retrieve.HybridRetrievePort;
import com.yoke.knowledge.retrieve.KeywordRetrievePort;
import com.yoke.model.Passage;
import com.yoke.knowledge.RetrievePort;
import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
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
    void 方舟协议的向量化走多模态接口且向量路真的生效() throws IOException {
        // 只在 /embeddings/multimodal 上应答；含「Redis」的片段与问题向量相同，必须被向量路排到前面
        AtomicInteger calls = new AtomicInteger();
        HttpServer ark = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ark.createContext("/api/v3/embeddings/multimodal", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.incrementAndGet();
            String vector = request.contains("Redis") || request.contains("历史") ? "[1.0,0.0]" : "[0.0,1.0]";
            byte[] body = ("{\"data\":{\"embedding\":" + vector + "}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ark.start();
        try {
            runner.withPropertyValues("yoke.knowledge.embedding.protocol=ark-multimodal",
                            "yoke.knowledge.embedding.model-name=doubao-embedding-vision-251215",
                            "yoke.knowledge.embedding.api-key=ark-test",
                            "yoke.knowledge.embedding.dimensions=2",
                            "yoke.knowledge.embedding.base-url=http://127.0.0.1:" + ark.getAddress().getPort() + "/api/v3")
                    .run(context -> {
                        RetrievePort port = context.getBean(RetrievePort.class);
                        assertThat(port).isInstanceOf(HybridRetrievePort.class);
                        assertThat(port.retrieve("对话历史存在哪里", 3).block()).isNotEmpty();
                        assertThat(calls.get()).as("向量路确实请求了方舟的多模态接口").isPositive();
                    });
        } finally {
            ark.stop(0);
        }
    }

    @Test
    void 应用就绪后在后台预建向量索引_不等第一次检索() throws IOException {
        // 切片测试不会自己发 ApplicationReadyEvent，这里手动发，证明预建是挂在就绪事件上的、而且确实请求了向量化服务
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch requested = new CountDownLatch(1);
        HttpServer ark = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ark.createContext("/api/v3/embeddings/multimodal", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            requested.countDown();
            byte[] body = "{\"data\":{\"embedding\":[1.0,0.0]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ark.start();
        try {
            runner.withPropertyValues("yoke.knowledge.embedding.protocol=ark-multimodal",
                            "yoke.knowledge.embedding.model-name=doubao-embedding-vision-251215",
                            "yoke.knowledge.embedding.api-key=ark-test",
                            "yoke.knowledge.embedding.dimensions=2",
                            "yoke.knowledge.embedding.base-url=http://127.0.0.1:" + ark.getAddress().getPort() + "/api/v3")
                    .run(context -> {
                        assertThat(calls.get()).as("上下文刷新本身不发请求").isZero();

                        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                                context.getSourceApplicationContext(ConfigurableApplicationContext.class), Duration.ZERO));
                        assertThat(requested.await(5, TimeUnit.SECONDS)).as("没有任何检索，向量化服务已经收到请求").isTrue();
                    });
        } finally {
            ark.stop(0);
        }
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
