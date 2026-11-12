package com.yoke.config;

import java.util.Objects;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisConfigurationTests {

    private static YokeProperties withUri(String uri) {
        return new YokeProperties(new YokeProperties.Redis(uri, "yoke:"), new YokeProperties.Compaction(30, 10), "/tmp/yoke",
                new YokeProperties.Knowledge(new YokeProperties.Knowledge.Embedding(null, null, null, null, null)),
                new YokeProperties.Mcp(java.util.List.of()));
    }

    private static String allMessages(Throwable error) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = error; Objects.nonNull(t); t = t.getCause()) {
            all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void 格式非法的连接串报错时不带出密码() {
        assertThatThrownBy(() -> new RedisConfiguration().jedis(withUri("redis://user:s3cret@[bad host:6379")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法连接 Redis")
                .satisfies(error -> assertThat(allMessages(error)).doesNotContain("s3cret"));
    }

    @Test
    void 连不上时报错也不带出密码() {
        assertThatThrownBy(() -> new RedisConfiguration().jedis(withUri("redis://:s3cret@127.0.0.1:1")))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(error -> assertThat(allMessages(error)).doesNotContain("s3cret"));
    }

    @Test
    void 脱敏只替换用户信息() {
        assertThat(RedisConfiguration.redact("redis://:s3cret@host:6379")).isEqualTo("redis://***@host:6379");
        assertThat(RedisConfiguration.redact("redis://host:6379")).isEqualTo("redis://host:6379");
    }

    @Test
    void 密码里有未编码的at符号和斜杠等笔误时脱敏仍然有效() {
        for (String uri : new String[] {
                "redis://:p@ss@host:6379", "redis://user:p@ss@host:6379", "redis://:pa/ss@host:6379",
                "redis://:pa?ss@host:6379", "redis://:pa#ss@host:6379", "redis://:p@ss@host:6379/0?timeout=1"}) {
            String redacted = RedisConfiguration.redact(uri);
            assertThat(redacted).as(uri).startsWith("redis://***@").doesNotContain("ss", "pa", "user");
        }
    }

    @Test
    void 这些笔误连接串在连不上时报错信息也不带出密码片段() {
        for (String uri : new String[] {"redis://:Pw9x@ss@127.0.0.1:1", "redis://u:Pw9x/ss@127.0.0.1:1", "redis://:Pw9x?ss@127.0.0.1:1"}) {
            assertThatThrownBy(() -> new RedisConfiguration().jedis(withUri(uri)))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(error -> assertThat(allMessages(error)).as(uri).doesNotContain("Pw9x"));
        }
    }

    @Test
    void scheme不合法时报错且不带出密码() {
        assertThatThrownBy(() -> new RedisConfiguration().jedis(withUri("http://:Pw9x@host:6379")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须是 redis:// 或 rediss:// 开头的连接串")
                .hasMessageContaining("YOKE_REDIS_URI")
                .satisfies(error -> assertThat(allMessages(error)).doesNotContain("Pw9x"));
    }

    @Test
    void Redis配置的toString不带出密码() {
        var redis = new YokeProperties.Redis("redis://user:Pw9x@ss@127.0.0.1:6379", "yoke:");

        assertThat(redis.toString()).doesNotContain("Pw9x", "user").contains("127.0.0.1:6379");
        assertThat(withUri("redis://:Pw9x@h").toString()).doesNotContain("Pw9x");
    }
}
