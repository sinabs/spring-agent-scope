package com.yoke.config;

import redis.clients.jedis.RedisClient;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 的健康检查。Yoke 直接用 Jedis，没有 Spring Data Redis 的连接工厂，Boot 不会自动提供 Redis 的健康指示器，
 * 所以这里补一个：ping 失败 /actuator/health 就是 DOWN，编排系统据此摘掉实例。
 * 只报 UP/DOWN，不带连接串和异常信息（health 端点不需要也不应该泄露它们）。
 */
@Configuration(proxyBeanMethods = false)
public class RedisHealthConfiguration {

    /** Bean 名 redisHealthIndicator 决定它在 health 里的组件名是 redis。 */
    @Bean
    public HealthIndicator redisHealthIndicator(RedisClient jedis) {
        return () -> {
            try {
                jedis.ping();
                return Health.up().build();
            } catch (RuntimeException e) {
                return Health.down().build();
            }
        };
    }
}
