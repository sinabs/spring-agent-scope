package com.yoke;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证各环境的配置文件按预期加载。
 *
 * <p>local 和 prod 的密钥来自环境变量 OPENAI_API_KEY，这里用同名属性代替，避免依赖本机环境。
 */
class ProfilesTests {

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
