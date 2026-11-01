package com.yoke.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Yoke 自己的配置（前缀 {@code yoke}），与 AgentScope 官方的 {@code agentscope.*} 分开。
 *
 * @param redis      Redis 连接与键前缀
 * @param compaction 对话压缩的触发与保留条数
 * @param workspace  HarnessAgent 的工作区目录，放压缩前转存的消息、记忆等本地文件
 * @param knowledge  知识库检索（可选的向量化配置）
 * @param mcp        要接入的外部 MCP 服务，默认一个也不接
 */
@Validated
@ConfigurationProperties(prefix = "yoke")
public record YokeProperties(
        @Valid @DefaultValue Redis redis,
        @DefaultValue Compaction compaction,
        @NotBlank String workspace,
        @Valid @DefaultValue Knowledge knowledge,
        @Valid @DefaultValue Mcp mcp) {

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
            return "Redis[uri=" + (uri == null ? null : RedisConfiguration.redact(uri)) + ", keyPrefix=" + keyPrefix + "]";
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

    /**
     * 知识库检索配置。
     *
     * @param embedding 向量化服务；不配置时只用关键词检索
     */
    public record Knowledge(@DefaultValue Embedding embedding) {

        /**
         * 向量化服务（OpenAI 兼容的 {@code /embeddings} 接口），{@code modelName} 和 {@code apiKey} 都有值才启用。
         *
         * @param modelName  向量模型名，例如 text-embedding-3-small；为空表示不启用向量检索
         * @param baseUrl    接口根地址，默认 OpenAI 官方；用 DashScope 等兼容服务时改它
         * @param apiKey     密钥，只从环境变量或外部配置提供，不要写进仓库
         * @param dimensions 向量维度，可选；不传用模型默认值
         */
        public record Embedding(
                String modelName,
                @DefaultValue("https://api.openai.com/v1") String baseUrl,
                String apiKey,
                Integer dimensions) {

            public boolean enabled() {
                return StringUtils.hasText(modelName) && StringUtils.hasText(apiKey);
            }

            /** 密钥不进日志和调试输出。 */
            @Override
            public String toString() {
                return "Embedding[modelName=" + modelName + ", baseUrl=" + baseUrl + ", apiKey="
                        + (StringUtils.hasText(apiKey) ? "***" : null) + ", dimensions=" + dimensions + "]";
            }
        }
    }

    /**
     * 外部 MCP 服务配置。
     *
     * @param servers 要接入的服务列表，默认为空：不配置就不接任何 MCP，也不影响启动
     */
    public record Mcp(@DefaultValue List<@Valid McpServer> servers) {

        public Mcp {
            servers = servers == null ? List.of() : List.copyOf(servers);
            long distinct = servers.stream().map(McpServer::name).distinct().count();
            if (distinct != servers.size()) {
                throw new IllegalArgumentException("yoke.mcp.servers 里的 name 不能重复：每个服务的工具靠它区分和释放");
            }
        }
    }

    /**
     * 一个 MCP 服务。
     *
     * <p><b>只允许 HTTP 传输</b>（{@code sse} 或 {@code streamable-http}）。{@code stdio} 会让服务端按配置里的命令行
     * 去启动本地进程：配置一旦被改动或被注入，就等于远程代码执行；多副本、容器里也没有可靠的进程管理。
     * 所以填 {@code stdio} 或任何别的值，应用启动时就失败，而不是等到运行时。
     *
     * @param name      服务名，在列表里唯一，用于日志和释放
     * @param transport 传输方式，{@code sse} 或 {@code streamable-http}
     * @param url       服务地址
     * @param timeout   单次请求超时，默认 30 秒
     * @param headers   每个请求附带的 HTTP 头，通常放认证令牌；可选
     */
    public record McpServer(
            @NotBlank String name,
            @NotBlank String transport,
            @NotBlank String url,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue Map<String, String> headers) {

        public static final String SSE = "sse";
        public static final String STREAMABLE_HTTP = "streamable-http";
        private static final Set<String> ALLOWED = Set.of(SSE, STREAMABLE_HTTP);

        public McpServer {
            String normalized = transport == null ? "" : transport.trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED.contains(normalized)) {
                throw new IllegalArgumentException("yoke.mcp.servers[" + name + "].transport=" + transport
                        + " 不被允许：运行态只支持 HTTP 传输（sse 或 streamable-http）。"
                        + "stdio 会按配置启动本地进程，存在命令注入风险，所以被拒绝");
            }
            transport = normalized;
            if (name == null || name.isBlank() || url == null || url.isBlank()) {
                throw new IllegalArgumentException("yoke.mcp.servers 的 name 和 url 不能为空");
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("yoke.mcp.servers[" + name + "].url 必须以 http:// 或 https:// 开头");
            }
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                timeout = Duration.ofSeconds(30);
            }
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        /** 地址和请求头里可能带令牌：toString 只留协议、主机和端口，请求头只列名字。 */
        @Override
        public String toString() {
            String origin;
            try {
                URI uri = URI.create(url);
                origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            } catch (IllegalArgumentException e) {
                origin = "<invalid>";
            }
            return "McpServer[name=" + name + ", transport=" + transport + ", url=" + origin
                    + ", timeout=" + timeout + ", headers=" + headers.keySet() + "]";
        }
    }
}
