package com.yoke.nacos;

import java.net.URI;
import java.util.Objects;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint;
import com.alibaba.nacos.api.exception.NacosException;
import io.a2a.spec.AgentCard;
import io.a2a.spec.TransportProtocol;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistry;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryProperties;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryTransportProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * 把 Agent Card 和服务端点注册到 Nacos，关闭时注销端点。
 *
 * <p>没有走框架的 {@code AgentScopeA2aServer.postEndpointReady()} 注册器链：那条链会把注册失败吞成一条 ERROR 日志，
 * 应用照常启动但永远不会被发现。这里直接调用 {@link NacosA2aRegistry}，失败就抛出，让应用启动失败。
 * 调用时机是应用就绪之后（{@code ApplicationReadyEvent}），此时 HTTP 端口已经在监听，别人发现后马上能调用。
 */
@Slf4j
public class NacosA2aRegistrar {

    private final AiService aiService;
    private final boolean registerAsLatest;
    private AgentCard registered;
    private AgentEndpoint endpoint;

    public NacosA2aRegistrar(AiService aiService, boolean registerAsLatest) {
        this.aiService = aiService;
        this.registerAsLatest = registerAsLatest;
    }

    /** 注册 Agent Card 和 JSON-RPC 端点（地址取自 Card 自己的 url，与对外公布的一致）。 */
    public synchronized void register(AgentCard card) {
        URI uri = URI.create(card.url());
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        int endpointPort = uri.getPort() > 0 ? uri.getPort() : tls ? 443 : 80;
        NacosA2aRegistryProperties props = NacosA2aRegistryProperties.builder().setAsLatest(registerAsLatest).build();
        props.addTransport(NacosA2aRegistryTransportProperties.builder()
                .transport(TransportProtocol.JSONRPC.asString()).host(uri.getHost()).port(endpointPort)
                .path(uri.getPath()).supportTls(tls).build());
        new NacosA2aRegistry(aiService).registerAgent(card, props);
        this.registered = card;
        // 注销时给出和注册时完全一致的端点（实测只给地址和端口的简写形式也能注销，这里不依赖这一点）。
        // 注意注销在服务端是异步生效的，实测要几百毫秒
        AgentEndpoint toRemove = new AgentEndpoint();
        toRemove.setTransport(TransportProtocol.JSONRPC.asString());
        toRemove.setAddress(uri.getHost());
        toRemove.setPort(endpointPort);
        toRemove.setPath(uri.getPath());
        toRemove.setSupportTls(tls);
        toRemove.setVersion(card.version());
        this.endpoint = toRemove;
        log.info("已把 Agent {} 注册到 Nacos（{}:{}）", card.name(), uri.getHost(), endpointPort);
    }

    /** 注销端点；Agent Card 本身留在 Nacos 里（它是配置数据，不是在线状态）。 */
    public synchronized void deregister() {
        if (Objects.isNull(registered)) {
            return;
        }
        try {
            aiService.deregisterAgentEndpoint(registered.name(), endpoint);
            log.info("已从 Nacos 注销 Agent {} 的端点", registered.name());
        } catch (NacosException | RuntimeException e) {
            log.warn("从 Nacos 注销端点失败：{}（连接关闭后临时实例也会被服务端摘除）", e.getClass().getSimpleName());
        }
        registered = null;
    }
}
