package com.yoke.config;

import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Yoke 自己的配置（前缀 {@code yoke}），与 AgentScope 官方的 {@code agentscope.*} 分开。
 *
 * @param redis      Redis 连接与键前缀
 * @param compaction 对话压缩的触发与保留条数
 * @param workspace  HarnessAgent 的工作区目录，放压缩前转存的消息、记忆等本地文件
 */
@Validated
@ConfigurationProperties(prefix = "yoke")
public record YokeProperties(
        @Valid @DefaultValue Redis redis,
        @DefaultValue Compaction compaction,
        @NotBlank String workspace) {

    /**
     * Redis 配置。
     *
     * @param uri       连接串，例如 {@code redis://127.0.0.1:6379}；没有默认值，缺失时绑定失败、应用无法启动。
     *                  环境变量未设置时 {@code ${YOKE_REDIS_URI}} 会原样留在值里（不会报占位符错误），所以在 RedisConfiguration 里校验格式。
     *                  不用 {@code @Pattern}：绑定失败的分析信息会原样打印属性值，连接串里的密码会被带出来
     * @param keyPrefix 所有键的统一前缀，多个应用共用一个 Redis 时靠它隔离
     */
    public record Redis(
            @NotBlank String uri, @DefaultValue("yoke:") String keyPrefix) {

        /** record 自动生成的 toString 会原样带出连接串，日志或调试输出里就泄漏密码，所以这里脱敏。 */
        @Override
        public String toString() {
            return "Redis[uri=" + Optional.ofNullable(uri).map(RedisConfiguration::redact).orElse(null) + ", keyPrefix=" + keyPrefix + "]";
        }
    }

    /**
     * 对话压缩配置，只按消息条数触发。
     *
     * <p>非法值（小于等于 0）回落到默认值；保留条数必须小于触发条数，否则一次压缩什么也切不出来，
     * 这种组合同样整体回落到默认值。
     *
     * @param triggerMessages 对话消息达到这个条数就触发压缩
     * @param keepMessages    压缩后原样保留最近多少条
     */
    public record Compaction(
            @DefaultValue("30") int triggerMessages,
            @DefaultValue("10") int keepMessages) {

        private static final Logger log = LoggerFactory.getLogger(Compaction.class);

        public static final int DEFAULT_TRIGGER_MESSAGES = 30;
        public static final int DEFAULT_KEEP_MESSAGES = 10;

        public Compaction {
            if (triggerMessages <= 0 || keepMessages <= 0 || keepMessages >= triggerMessages) {
                log.warn("压缩配置不合法（trigger-messages={}, keep-messages={}），回落到默认值 {} / {}",
                        triggerMessages, keepMessages, DEFAULT_TRIGGER_MESSAGES, DEFAULT_KEEP_MESSAGES);
                triggerMessages = DEFAULT_TRIGGER_MESSAGES;
                keepMessages = DEFAULT_KEEP_MESSAGES;
            }
        }
    }
}
