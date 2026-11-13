package com.yoke.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import cn.hutool.core.util.StrUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Yoke 自己的配置（前缀 {@code yoke}），与 AgentScope 官方的 {@code agentscope.*} 分开。
 *
 * @param redis      Redis 连接与键前缀
 * @param compaction 对话压缩的触发与保留条数
 * @param workspace  HarnessAgent 的工作区目录，放压缩前转存的消息、记忆等本地文件
 * @param knowledge  知识库检索（可选的向量化配置）
 * @param mcp        要接入的外部 MCP 服务，默认一个也不接
 * @param a2a        A2A 互通：对外暴露的服务端（默认关闭）和要调用的远端 Agent（默认没有）
 * @param nacos      Nacos 接线：A2A 注册发现、Prompt 热更新，默认全部关闭
 */
@Validated
@ConfigurationProperties(prefix = "yoke")
public record YokeProperties(
        @Valid @DefaultValue Redis redis,
        @DefaultValue Compaction compaction,
        @NotBlank String workspace,
        @Valid @DefaultValue Knowledge knowledge,
        @Valid @DefaultValue Mcp mcp,
        @Valid @DefaultValue A2a a2a,
        @Valid @DefaultValue Nacos nacos) {

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
        public @NonNull String toString() {
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
    @Slf4j
    public record Compaction(
            @DefaultValue("30") int triggerMessages,
            @DefaultValue("10") int keepMessages) {

        public static final int DEFAULT_TRIGGER_MESSAGES = 30;
        public static final int DEFAULT_KEEP_MESSAGES = 10;

        public Compaction {
            // keepMessages > 0 且 keepMessages < triggerMessages 已经蕴含 triggerMessages > 0，所以不用单独判断触发条数
            if (keepMessages <= 0 || keepMessages >= triggerMessages) {
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
         * 向量化服务，{@code modelName} 和 {@code apiKey} 都有值才启用。
         *
         * @param modelName  向量模型名，例如 text-embedding-3-small；为空表示不启用向量检索
         * @param baseUrl    接口根地址，默认 OpenAI 官方；用 DashScope 等兼容服务时改它
         * @param apiKey     密钥，只从环境变量或外部配置提供，不要写进仓库
         * @param dimensions 向量维度，可选；不传用模型默认值。方舟多模态协议会校验返回的维度与它一致
         * @param protocol   接口协议，默认 OpenAI 兼容的 {@code /embeddings}；火山方舟的多模态向量模型要选 ark-multimodal
         */
        public record Embedding(
                String modelName,
                @DefaultValue("https://api.openai.com/v1") String baseUrl,
                String apiKey,
                Integer dimensions,
                @DefaultValue("openai") Protocol protocol) {

            /** 向量化接口的协议。 */
            public enum Protocol {
                /** OpenAI 兼容的 {@code POST /embeddings}，支持批量。 */
                OPENAI,
                /** 火山方舟的 {@code POST /embeddings/multimodal}，一条文本一次请求。 */
                ARK_MULTIMODAL
            }

            public boolean enabled() {
                return StrUtil.isNotBlank(modelName) && StrUtil.isNotBlank(apiKey);
            }

            /** 密钥不进日志和调试输出。 */
            @Override
            public @NonNull String toString() {
                return "Embedding[modelName=" + modelName + ", baseUrl=" + baseUrl + ", apiKey="
                        + (StrUtil.isNotBlank(apiKey) ? "***" : null) + ", dimensions=" + dimensions + ", protocol=" + protocol + "]";
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
            servers = Optional.ofNullable(servers).map(List::copyOf).orElse(List.of());
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
            String normalized = Optional.ofNullable(transport).map(t -> t.trim().toLowerCase(Locale.ROOT)).orElse("");
            if (!ALLOWED.contains(normalized)) {
                throw new IllegalArgumentException("yoke.mcp.servers[" + name + "].transport=" + transport
                        + " 不被允许：运行态只支持 HTTP 传输（sse 或 streamable-http）。"
                        + "stdio 会按配置启动本地进程，存在命令注入风险，所以被拒绝");
            }
            transport = normalized;
            if (StrUtil.isBlank(name) || StrUtil.isBlank(url)) {
                throw new IllegalArgumentException("yoke.mcp.servers 的 name 和 url 不能为空");
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("yoke.mcp.servers[" + name + "].url 必须以 http:// 或 https:// 开头");
            }
            if (Objects.isNull(timeout) || timeout.isZero() || timeout.isNegative()) {
                timeout = Duration.ofSeconds(30);
            }
            headers = Optional.ofNullable(headers).map(Map::copyOf).orElse(Map.of());
        }

        /** 地址和请求头里可能带令牌：toString 只留协议、主机和端口，请求头只列名字。 */
        @Override
        public @NonNull String toString() {
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

    /**
     * A2A 互通配置。
     *
     * @param server 对外暴露的 A2A 服务端；默认关闭
     * @param remote 要调用的远端 A2A Agent；不配置就没有远端调用能力
     */
    public record A2a(@Valid @DefaultValue Server server, @Valid @DefaultValue Remote remote) {

        /** 全部使用默认值：服务端关闭、没有远端。 */
        public static A2a disabled() {
            return new A2a(new Server(false, null, null, Duration.ofSeconds(120), Server.DEFAULT_MAX_BODY_BYTES, new Card(null, null, "1.0.0", List.of())),
                    new Remote(null, null, null, Duration.ofSeconds(30)));
        }

        /**
         * A2A 服务端。<b>开启就是一个等价于 {@code /api/chat} 的入口</b>：Yoke 目前没有任何认证，
         * 调用方还能自报 userId。生产必须放在网关后面，或至少配置 {@code token}。
         *
         * @param enabled   是否注册 Agent Card 和 JSON-RPC 端点，默认 false
         * @param token     可选的 Bearer Token，只保护 JSON-RPC 端点（Agent Card 公开，里面会声明需要 Bearer）；
         *                  来自环境变量，不配置就不校验（启动时打 WARN）
         * @param publicUrl 外部能访问到的根地址，例如 {@code https://yoke.example.com}；写进 Agent Card 和 Nacos 注册，
         *                  不配置时用 {@code http://localhost:{server.port}}
         * @param timeout   一次调用的总超时，默认 120 秒
         * @param maxBodyBytes JSON-RPC 请求体的大小上限（字节），默认 256 KB，超过返回 -32600；非法值回落到默认
         * @param card      Agent Card 里的描述信息
         */
        public record Server(
                @DefaultValue("false") boolean enabled,
                String token,
                String publicUrl,
                @DefaultValue("120s") Duration timeout,
                @DefaultValue("262144") int maxBodyBytes,
                @Valid @DefaultValue Card card) {

            public static final int DEFAULT_MAX_BODY_BYTES = 256 * 1024;

            /** token 至少这么长，否则启动失败（见 A2aServerConfiguration.requireStrongToken）。 */
            public static final int MIN_TOKEN_LENGTH = 16;

            public Server {
                if (Objects.isNull(timeout) || timeout.isZero() || timeout.isNegative()) {
                    timeout = Duration.ofSeconds(120);
                }
                if (maxBodyBytes <= 0) {
                    maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
                }
            }

            public boolean tokenConfigured() {
                return StrUtil.isNotBlank(token);
            }

            /** token 不进日志和调试输出。 */
            @Override
            public String toString() {
                return "Server[enabled=" + enabled + ", token=" + (tokenConfigured() ? "***" : null)
                        + ", publicUrl=" + publicUrl + ", timeout=" + timeout + ", card=" + card + "]";
            }
        }

        /**
         * Agent Card 的描述信息；名字缺省时用 {@code agentscope.agent.name}，描述缺省时用一句默认说明。
         *
         * @param name        对外名字，也是 Nacos 里的 Agent 名
         * @param description 描述
         * @param version     版本号，默认 1.0.0
         * @param skills      对外宣告的技能（只是描述，不会让 Agent 多出任何工具）
         */
        public record Card(
                String name, String description, @DefaultValue("1.0.0") String version,
                @DefaultValue List<@Valid Skill> skills) {

            public Card {
                skills = Optional.ofNullable(skills).map(List::copyOf).orElse(List.of());
            }
        }

        /**
         * Agent Card 里的一个技能条目。
         *
         * @param id          技能标识
         * @param name        技能名
         * @param description 说明
         * @param tags        标签
         */
        public record Skill(
                @NotBlank String id, @NotBlank String name, String description, @DefaultValue List<String> tags) {

            public Skill {
                tags = Optional.ofNullable(tags).map(List::copyOf).orElse(List.of());
            }
        }

        /**
         * 远端 A2A Agent。地址只来自配置，请求体不能影响它（防 SSRF）。两种来源，同一时刻只用一种：
         * <ul>
         *   <li>{@code url}：well-known 直连，读取 {@code url + /.well-known/agent-card.json}；</li>
         *   <li>{@code agentName}：到 Nacos 里按名字发现，要求 {@code yoke.nacos.a2a.enabled=true}；两个都配时用 Nacos。</li>
         * </ul>
         *
         * @param url       远端根地址，例如 {@code http://other:8080}
         * @param agentName Nacos 里的远端 Agent 名
         * @param token     调用远端时带的 Bearer Token，可选
         * @param timeout   一次调用的超时，默认 30 秒
         */
        public record Remote(String url, String agentName, String token, @DefaultValue("30s") Duration timeout) {

            public Remote {
                if (Objects.isNull(timeout) || timeout.isZero() || timeout.isNegative()) {
                    timeout = Duration.ofSeconds(30);
                }
            }

            public boolean configured() {
                return StrUtil.isNotBlank(url) || StrUtil.isNotBlank(agentName);
            }

            /** 地址里可能带路径或参数，token 更不能进日志：只留名字、是否有 token。 */
            @Override
            public String toString() {
                return "Remote[url=" + url + ", agentName=" + agentName + ", token="
                        + (StrUtil.isNotBlank(token) ? "***" : null) + ", timeout=" + timeout + "]";
            }
        }
    }

    /**
     * Nacos 接线配置。所有开关默认关闭且互不绑死：只开 Prompt 热更新不需要开 A2A，反之亦然。
     * 任何一个开关打开，启动时都会先探活，连不上直接启动失败。
     *
     * @param serverAddr Nacos 地址，格式 {@code host:port}，默认 127.0.0.1:8848；AI 通道还需要 gRPC 端口（主端口 + 1000）
     * @param namespace  命名空间 ID（不是名称），不配置用 public
     * @param username   用户名，Nacos 开了鉴权才需要；来自环境变量
     * @param password   密码，来自环境变量，不进日志
     * @param a2a        A2A 注册发现
     * @param prompt     Prompt 热更新
     */
    public record Nacos(
            @DefaultValue("127.0.0.1:8848") String serverAddr,
            String namespace,
            String username,
            String password,
            @DefaultValue A2aSwitch a2a,
            @DefaultValue PromptSwitch prompt) {

        /** 全部使用默认值：所有开关关闭。 */
        public static Nacos disabled() {
            return new Nacos("127.0.0.1:8848", null, null, null, new A2aSwitch(false, false),
                    new PromptSwitch(false, "yoke-system-prompt", Duration.ofSeconds(2)));
        }

        /** 任何一个开关打开就需要连接 Nacos。 */
        public boolean anyEnabled() {
            return a2a.enabled() || prompt.enabled();
        }

        /** 密码不进日志和调试输出。 */
        @Override
        public String toString() {
            return "Nacos[serverAddr=" + serverAddr + ", namespace=" + namespace + ", username=" + username
                    + ", password=" + (StrUtil.isNotBlank(password) ? "***" : null) + ", a2a=" + a2a
                    + ", prompt=" + prompt + "]";
        }

        /**
         * A2A 注册发现开关：服务端开启时把 Agent Card 注册进去，配了 {@code yoke.a2a.remote.agent-name} 时从这里发现远端。
         *
         * @param enabled         是否启用，默认 false
         * @param registerAsLatest 注册时是否把这个版本设为最新版本
         */
        public record A2aSwitch(@DefaultValue("false") boolean enabled, @DefaultValue("false") boolean registerAsLatest) {
        }

        /**
         * Prompt 热更新开关：每轮对话把 Nacos 里最新的提示词追加到内置提示词之后。
         *
         * @param enabled 是否启用，默认 false
         * @param key     Nacos 里的 Prompt Key
         * @param timeout 每轮拉取的超时，默认 2 秒；超时按拉取失败处理（沿用内置提示词），不拖住对话
         */
        public record PromptSwitch(@DefaultValue("false") boolean enabled, @DefaultValue("yoke-system-prompt") String key,
                @DefaultValue("2s") Duration timeout) {

            public PromptSwitch {
                if (Objects.isNull(timeout) || timeout.isZero() || timeout.isNegative()) {
                    timeout = Duration.ofSeconds(2);
                }
            }
        }
    }
}
