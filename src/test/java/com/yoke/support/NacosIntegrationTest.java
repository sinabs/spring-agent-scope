package com.yoke.support;

import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 需要真实 Nacos 的测试的基类：Redis 和 Nacos 的地址都注入进来。Docker 不可用时整个类被跳过（CI 里失败）。
 * 子类按需自己打开 {@code yoke.nacos.*} 的开关。
 */
@ExtendWith(RequiresDocker.class)
public abstract class NacosIntegrationTest {

    @DynamicPropertySource
    static void infraProperties(DynamicPropertyRegistry registry) {
        registry.add("yoke.redis.uri", RedisCompose::uri);
        registry.add("yoke.nacos.server-addr", NacosCompose::serverAddr);
    }
}
