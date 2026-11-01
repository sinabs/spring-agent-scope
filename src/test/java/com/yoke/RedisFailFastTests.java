package com.yoke;

import com.yoke.support.RedisCompose;
import com.yoke.support.RequiresDocker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 配置有问题时应用必须在启动期失败，而不是等到第一个请求才暴露。
 * 这些测试不依赖 Docker：连的是一个没人监听的端口，或者干脆不给连接串。
 */
class RedisFailFastTests {

    private static SpringApplicationBuilder app(String profile) {
        return new SpringApplicationBuilder(YokeApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(profile)
                .properties("OPENAI_API_KEY=sk-test");
    }

    @Test
    void Redis不可达时上下文启动失败且提示清晰() {
        assertThatThrownBy(() -> app("test").properties("yoke.redis.uri=redis://:s3cret@127.0.0.1:1").run())
                .hasStackTraceContaining("无法连接 Redis")
                .hasStackTraceContaining("docker compose up -d")
                .as("报错信息里不能带出密码")
                .satisfies(error -> assertThat(rootMessages(error)).doesNotContain("s3cret"));
    }

    @Test
    void scheme不合法且带密码时启动失败信息也不含密码() {
        assertThatThrownBy(() -> app("test").properties("yoke.redis.uri=http://:Pw9x@h").run())
                .hasStackTraceContaining("必须是 redis:// 或 rediss:// 开头的连接串")
                .satisfies(error -> assertThat(rootMessages(error)).doesNotContain("Pw9x"));
    }

    @Test
    void 生产环境没有给Redis连接串时启动失败() {
        // prod 的连接串来自环境变量 YOKE_REDIS_URI，没有默认值
        assertThatThrownBy(() -> app("prod").run())
                .hasStackTraceContaining("yoke.redis.uri")
                .hasStackTraceContaining("必须是 redis:// 或 rediss:// 开头的连接串")
                .hasStackTraceContaining("YOKE_REDIS_URI");
    }

    @Test
    @ExtendWith(RequiresDocker.class)
    void Redis可达时正常启动() {
        try (ConfigurableApplicationContext context = app("test").properties("yoke.redis.uri=" + RedisCompose.uri()).run()) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    private static String rootMessages(Throwable error) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
