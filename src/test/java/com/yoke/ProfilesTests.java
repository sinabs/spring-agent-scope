package com.yoke;

import java.io.IOException;
import java.util.List;

import com.yoke.support.RedisIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证各环境的配置文件按预期加载。
 *
 * <p>local 和 prod 的密钥来自环境变量 OPENAI_API_KEY，这里用同名属性代替，避免依赖本机环境。
 * 启动完整上下文需要 Redis，连接串由共享的 Testcontainers 容器注入；Redis 连接串在各环境文件里的写法
 * 会被注入的值覆盖，所以单独直接读文件来断言。
 */
class ProfilesTests extends RedisIntegrationTest {

    private static Object yamlValue(String file, String key) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        return sources.getFirst().getProperty(key);
    }

    @Test
    void local的Redis连接串有本机默认值() throws IOException {
        assertThat(yamlValue("application-local.yml", "yoke.redis.uri"))
                .isEqualTo("${YOKE_REDIS_URI:redis://127.0.0.1:6379}");
    }

    @Test
    void prod的Redis连接串来自环境变量且没有默认值() throws IOException {
        assertThat(yamlValue("application-prod.yml", "yoke.redis.uri")).isEqualTo("${YOKE_REDIS_URI}");
    }

    @Test
    void test环境不写Redis连接串而是由测试注入() throws IOException {
        assertThat(yamlValue("application-test.yml", "yoke.redis.uri")).isNull();
    }

    @Test
    void 向量化密钥在三个环境里的写法一致_都不会让缺失变成启动失败() throws IOException {
        assertThat(yamlValue("application-local.yml", "yoke.knowledge.embedding.api-key")).isEqualTo("${YOKE_EMBEDDING_API_KEY:}");
        assertThat(yamlValue("application-prod.yml", "yoke.knowledge.embedding.api-key")).isEqualTo("${YOKE_EMBEDDING_API_KEY:}");
        // test 环境故意留空，保证测试不访问向量化服务
        assertThat(yamlValue("application-test.yml", "yoke.knowledge.embedding.api-key")).isIn("", null);
    }

    @Nested
    @SpringBootTest(properties = "OPENAI_API_KEY=sk-test")
    class 默认环境 {

        @Autowired
        Environment environment;

        @Test
        void 没有指定环境时激活local() {
            assertThat(environment.getDefaultProfiles()).contains("local");
            assertThat(environment.getProperty("logging.level.com.yoke")).isEqualTo("debug");
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class 测试环境 {

        @Autowired
        Environment environment;

        @Test
        void 使用假密钥() {
            assertThat(environment.getProperty("agentscope.openai.api-key")).isEqualTo("sk-test");
            assertThat(environment.getProperty("logging.level.com.yoke")).isNull();
            assertThat(environment.getProperty("yoke.redis.uri")).startsWith("redis://");
            assertThat(environment.getProperty("yoke.redis.key-prefix")).isEqualTo("yoke:");
            assertThat(environment.getProperty("logging.level.io.agentscope.harness.agent.tools.ToolFilter")).isEqualTo("ERROR");
            // 测试环境不启用向量检索，也不接任何 MCP 服务
            assertThat(environment.getProperty("yoke.knowledge.embedding.api-key", "")).isEmpty();
            assertThat(environment.getProperty("yoke.mcp.servers[0].name")).isNull();
        }
    }

    @Nested
    @SpringBootTest(properties = "OPENAI_API_KEY=sk-prod")
    @ActiveProfiles("prod")
    class 生产环境 {

        @Autowired
        Environment environment;

        @Test
        void 优雅停机且密钥来自环境变量() {
            assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
            assertThat(environment.getProperty("agentscope.openai.api-key")).isEqualTo("sk-prod");
            assertThat(environment.getProperty("logging.level.com.yoke")).isEqualTo("info");
        }
    }
}
