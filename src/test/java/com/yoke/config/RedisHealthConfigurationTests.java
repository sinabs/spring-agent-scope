package com.yoke.config;

import java.util.Objects;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.exceptions.JedisConnectionException;

import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisHealthConfigurationTests {

    private final RedisHealthConfiguration config = new RedisHealthConfiguration();

    @Test
    void ping成功时是UP() {
        RedisClient jedis = mock(RedisClient.class);
        when(jedis.ping()).thenReturn("PONG");

        assertThat(Objects.requireNonNull(config.redisHealthIndicator(jedis).health()).getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void ping失败时是DOWN且不带任何明细() {
        RedisClient jedis = mock(RedisClient.class);
        when(jedis.ping()).thenThrow(new JedisConnectionException("redis://:密码@host 连接失败"));

        var health = Objects.requireNonNull(config.redisHealthIndicator(jedis).health());

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }
}
