package com.yoke.support;

import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 需要真实 Redis 的测试的基类：把共享容器的地址注入 {@code yoke.redis.uri}。
 * Docker 不可用时整个类被跳过。
 */
@ExtendWith(RequiresDocker.class)
public abstract class RedisIntegrationTest {

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("yoke.redis.uri", RedisCompose::uri);
    }
}
