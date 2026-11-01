package com.yoke;

import java.util.UUID;

import com.yoke.controller.ChatController;
import com.yoke.model.ChatRequest;
import com.yoke.support.RecordingModel;
import com.yoke.support.RedisCompose;
import com.yoke.support.RequiresDocker;
import com.yoke.support.StubModelConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用两个互相独立的 Spring 上下文模拟「进程重启」：第一个上下文关闭后，第二个全新的上下文
 * （新的 Redis 连接、新的 Agent、新的内存）仍然能读到第一个写下的历史。
 */
@ExtendWith(RequiresDocker.class)
class RestartRecoveryTests {

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(YokeApplication.class, StubModelConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .properties("yoke.redis.uri=" + RedisCompose.uri())
                .run();
    }

    @Test
    void 重启后同一会话的历史仍在() {
        String session = "restart-" + UUID.randomUUID();

        try (ConfigurableApplicationContext before = start()) {
            before.getBean(ChatController.class).chat(new ChatRequest(session, "重启之前说的话", "dave")).block();
        }

        assertThat(RedisCompose.keys("yoke:session:dave/" + session + ":*")).as("第一个上下文关闭后，历史留在 Redis 里").isNotEmpty();

        try (ConfigurableApplicationContext after = start()) {
            RecordingModel model = after.getBean(RecordingModel.class);
            assertThat(model.received).as("全新的上下文里模型还没被调用过").isEmpty();

            after.getBean(ChatController.class).chat(new ChatRequest(session, "重启之后说的话", "dave")).block();

            assertThat(model.lastCallContains("重启之前说的话")).isTrue();
        }
    }
}
