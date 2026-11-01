package com.yoke.support;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Set;

import redis.clients.jedis.RedisClient;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * 读取根目录的 docker-compose.yml 拉起 Redis，整个测试 JVM 共用同一个容器实例。
 *
 * <p>容器第一次用到时才启动，JVM 退出时停止；Docker 不可用时 {@link #dockerAvailable()} 返回 false，
 * 依赖它的测试会被明确跳过（见 {@link RequiresDocker}）。
 */
public final class RedisCompose {

    private static final String SERVICE = "redis";
    private static final int REDIS_PORT = 6379;

    private static ComposeContainer compose;
    private static String uri;

    private RedisCompose() {
    }

    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    /** 共享 Redis 的连接串，形如 {@code redis://127.0.0.1:54321}。 */
    public static synchronized String uri() {
        if (uri == null) {
            start();
        }
        return uri;
    }

    /** 绕过应用，直接用独立的客户端查 Redis 里匹配的键，用来证明历史真的落在 Redis 而不是别处。 */
    public static Set<String> keys(String pattern) {
        try (RedisClient client = RedisClient.create(uri())) {
            return client.keys(pattern);
        }
    }

    private static void start() {
        int hostPort = freePort();
        // compose 文件里宿主机端口来自 REDIS_PORT，这里换成空闲端口，避免和本机正在跑的 Redis 冲突
        compose = new ComposeContainer(new File("docker-compose.yml"))
                .withEnv("REDIS_PORT", String.valueOf(hostPort))
                .withExposedService(SERVICE, REDIS_PORT, Wait.forHealthcheck());
        compose.start();
        Runtime.getRuntime().addShutdownHook(new Thread(compose::stop));
        uri = "redis://" + compose.getServiceHost(SERVICE, REDIS_PORT) + ":" + compose.getServicePort(SERVICE, REDIS_PORT);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("找不到空闲端口", e);
        }
    }
}
