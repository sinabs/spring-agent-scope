package com.yoke.config;

import java.util.regex.Pattern;

import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.DistributedStore;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.RedisClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 分布式存储：会话历史放在进程之外，重启和多副本都能按 (userId, sessionId) 接上。
 *
 * <p>启动时就 ping 一次：连不上 Redis 时应用直接启动失败，而不是等到第一个请求才发现历史存不下来。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(YokeProperties.class)
public class RedisConfiguration {

    private static final Pattern SCHEME = Pattern.compile("rediss?://.+", Pattern.CASE_INSENSITIVE);

    /** 单机 Jedis 客户端（内部是连接池）；容器关闭时由 Spring 调用 {@code close()} 释放连接。 */
    @Bean(destroyMethod = "close")
    public RedisClient jedis(YokeProperties properties) {
        String uri = properties.redis().uri();
        if (!SCHEME.matcher(uri).matches()) {
            throw new IllegalStateException("yoke.redis.uri 必须是 redis:// 或 rediss:// 开头的连接串；"
                    + "生产环境通过环境变量 YOKE_REDIS_URI 提供。当前值：" + redact(uri));
        }
        RedisClient jedis = null;
        try {
            jedis = RedisClient.create(uri);
            jedis.ping();
        } catch (RuntimeException e) {
            if (jedis != null) {
                jedis.close();
            }
            // 不把原始异常作为 cause：Jedis 对格式非法的连接串会在异常消息里原样回显，可能带出密码
            throw new IllegalStateException("无法连接 Redis（" + redact(uri)
                    + "），请确认 Redis 已启动：本地研发先执行 docker compose up -d，"
                    + "其他环境检查 yoke.redis.uri（原因：" + e.getClass().getSimpleName() + "）");
        }
        log.info("Redis 连接正常：{}", redact(uri));
        return jedis;
    }

    /** 同一个连接同时提供会话状态、文件键值等存储；HarnessAgent 的 {@code distributedStore} 用它。 */
    @Bean
    public DistributedStore distributedStore(RedisClient jedis, YokeProperties properties) {
        return RedisDistributedStore.fromJedis(jedis, properties.redis().keyPrefix());
    }

    /**
     * 报错信息和日志里不带用户名和密码。
     *
     * <p>不依赖 URI 解析：密码里未编码的 {@code @}、{@code /} 之类笔误会让 URI 解析认不出用户信息。
     * 这里把 {@code ://} 之后到最后一个 {@code @} 之间的内容整段替换，宁可多遮一点。
     */
    static String redact(String uri) {
        return uri.replaceFirst("(?<=://).*@", "***@");
    }
}
