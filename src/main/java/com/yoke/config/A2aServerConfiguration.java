package com.yoke.config;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.hutool.core.util.StrUtil;
import com.yoke.a2a.A2aGateway;
import com.yoke.a2a.runner.YokeAgentRunner;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import io.a2a.spec.HTTPAuthSecurityScheme;
import io.a2a.spec.SecurityScheme;
import io.a2a.spec.TransportProtocol;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * A2A 服务端装配，{@code yoke.a2a.server.enabled=true} 才生效，默认整个类都不存在。
 *
 * <p>为什么手写装配而不用官方的 {@code agentscope-a2a-spring-boot-starter}：starter 的自动装配只认
 * {@code ReActAgent} / {@code ReActAgent.Builder}，接不上每请求新建 HarnessAgent 的工厂；
 * 它自带的控制器也没有认证、不校验标识格式，Card 里的 streaming 还固定写成 true。
 * 这里只用 server 模块的 {@link AgentScopeA2aServer} 和 {@code AgentRunner}，端点自己暴露（见 {@link A2aGateway}）。
 * 两种做法不能同时启用，本项目不引入 starter，所以不存在冲突。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "yoke.a2a.server", name = "enabled", havingValue = "true")
public class A2aServerConfiguration {

    /** JSON-RPC 端点的路径，与 {@code A2aApi} 里的映射一致。 */
    public static final String RPC_PATH = "/a2a";

    private static final String SCHEME_NAME = "bearer";

    @Bean
    public YokeAgentRunner yokeAgentRunner(HarnessAgentFactory factory, AgentscopeProperties agentscope, YokeProperties yoke) {
        YokeProperties.A2a.Card card = yoke.a2a().server().card();
        String name = StrUtil.isNotBlank(card.name()) ? card.name() : agentscope.getAgent().getName();
        String description = StrUtil.isNotBlank(card.description()) ? card.description()
                : "Yoke：基于 AgentScope 的对话 Agent，通过 A2A 协议提供同步问答";
        return new YokeAgentRunner(factory, name, description, yoke.a2a().server().timeout());
    }

    /** 处理 A2A 请求的线程：虚拟线程，框架的处理链是阻塞的。 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService a2aExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public AgentScopeA2aServer agentScopeA2aServer(YokeAgentRunner runner, YokeProperties yoke, Environment env,
            ExecutorService a2aExecutor) {
        YokeProperties.A2a.Server server = yoke.a2a().server();
        requireStrongToken(server);
        TransportProperties transport = resolveTransport(server, env);

        ConfigurableAgentCard.Builder card = new ConfigurableAgentCard.Builder()
                .name(runner.getAgentName())
                .description(runner.getAgentDescription())
                .version(server.card().version())
                .skills(server.card().skills().stream().map(A2aServerConfiguration::skill).toList());
        if (server.tokenConfigured()) {
            // Card 与实际行为一致：配了 token 就声明需要 Bearer
            Map<String, SecurityScheme> schemes = Map.of(SCHEME_NAME,
                    new HTTPAuthSecurityScheme(null, "bearer", "在 Authorization 请求头里带 Bearer Token"));
            card.securitySchemes(schemes).security(List.of(Map.of(SCHEME_NAME, List.of())));
        } else {
            log.warn("A2A 服务端已开启但没有配置 yoke.a2a.server.token：{} 是一个无认证入口，"
                    + "等价于 /api/chat，调用方还能自报 userId。生产必须放在网关后面或配置 token", RPC_PATH);
        }
        return AgentScopeA2aServer.builder(runner)
                .agentCard(card.build())
                .withTransport(transport)
                .executor(a2aExecutor)
                .build();
    }

    @Bean
    public A2aGateway a2aGateway(AgentScopeA2aServer server, YokeAgentRunner runner, YokeProperties yoke) {
        return new A2aGateway(server, runner, publishedCard(server.getAgentCard()), yoke.a2a().server().token(),
                yoke.a2a().server().maxBodyBytes());
    }

    /**
     * 配置了 token 但太短就启动失败，而不是只打 WARN：Bearer Token 是唯一的保护，
     * 短 token（「123456」「test」）等于没有，而 WARN 在容器日志里很容易被忽略。
     * 报错信息不含 token 本身。没配置 token 的情形另有 WARN，不在这里处理。
     */
    static void requireStrongToken(YokeProperties.A2a.Server server) {
        if (server.tokenConfigured() && server.token().length() < YokeProperties.A2a.Server.MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("yoke.a2a.server.token 太短（至少 " + YokeProperties.A2a.Server.MIN_TOKEN_LENGTH
                    + " 个字符，当前 " + server.token().length() + " 个）。请用足够随机的值，例如 openssl rand -hex 24，并通过环境变量 YOKE_A2A_TOKEN 注入");
        }
    }

    /**
     * 对外公布的 Agent Card。官方转换器把 capabilities.streaming 固定写成 true（不可配置），
     * 而 Yoke 只提供同步的 message/send，所以这里改成 false，让 Card 说真话。
     */
    static AgentCard publishedCard(AgentCard card) {
        AgentCapabilities original = card.capabilities();
        AgentCapabilities honest = new AgentCapabilities(false, false, false,
                Optional.ofNullable(original).map(AgentCapabilities::extensions).orElse(List.of()));
        return new AgentCard.Builder(card).capabilities(honest).build();
    }

    private static AgentSkill skill(YokeProperties.A2a.Skill skill) {
        return new AgentSkill(skill.id(), skill.name(),
                StrUtil.isNotBlank(skill.description()) ? skill.description() : skill.name(),
                skill.tags(), List.of(), null, null, null);
    }

    static TransportProperties resolveTransport(YokeProperties.A2a.Server server, Environment env) {
        String raw = Optional.ofNullable(server.publicUrl()).filter(StrUtil::isNotBlank)
                .map(String::trim)
                .orElseGet(() -> "http://localhost:" + env.getProperty("server.port", "8080"));
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            throw new IllegalStateException("yoke.a2a.server.public-url 必须以 http:// 或 https:// 开头");
        }
        URI base;
        try {
            base = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("yoke.a2a.server.public-url 不是合法的 URL");
        }
        if (Objects.isNull(base.getHost()) || Objects.nonNull(base.getRawUserInfo())
                || Objects.nonNull(base.getRawQuery()) || Objects.nonNull(base.getRawFragment())) {
            throw new IllegalStateException("yoke.a2a.server.public-url 格式不合法：需要有效的协议与主机，且不能带用户信息、查询参数或片段");
        }
        boolean tls = "https".equalsIgnoreCase(base.getScheme());
        int port = base.getPort() > 0 ? base.getPort() : tls ? 443 : 80;
        String basePath = Optional.ofNullable(base.getPath()).orElse("").replaceAll("/+$", "");
        return TransportProperties.builder(TransportProtocol.JSONRPC.asString())
                .host(base.getHost()).port(port).path(basePath + RPC_PATH).supportTls(tls).build();
    }
}
