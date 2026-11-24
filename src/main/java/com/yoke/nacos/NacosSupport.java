package com.yoke.nacos;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

import cn.hutool.core.util.StrUtil;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.yoke.config.YokeProperties;
import lombok.extern.slf4j.Slf4j;


/**
 * Nacos 客户端的创建与启动探活。
 *
 * <p>三份数据（Prompt / Agent Card / Skill）走同一条 gRPC 通道，所以探活只需要一次：
 * 用一个不存在的 Agent 名调 {@code getAgentCard}，服务端回 404（未找到）说明通道是通的、服务端认识 AI 协议；
 * 其余结果（连接被拒、超时、服务端不认识请求即版本过低、鉴权失败）都启动失败，把问题前置到启动期，
 * 而不是等到第一次注册或第一次对话才发现。报错信息不含用户名和密码。
 */
@Slf4j
public final class NacosSupport {

    private NacosSupport() {
    }

    static Properties clientProperties(YokeProperties.Nacos nacos) {
        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, nacos.serverAddr());
        if (StrUtil.isNotBlank(nacos.namespace())) {
            props.setProperty(PropertyKeyConst.NAMESPACE, nacos.namespace());
        }
        if (StrUtil.isNotBlank(nacos.username())) {
            props.setProperty(PropertyKeyConst.USERNAME, nacos.username());
        }
        if (StrUtil.isNotBlank(nacos.password())) {
            props.setProperty(PropertyKeyConst.PASSWORD, nacos.password());
        }
        return props;
    }

    /**
     * Nacos 客户端默认把日志写到 {@code ~/logs/nacos}、把服务快照缓存写到 {@code ~/nacos}，污染用户主目录，
     * 还可能和同一台机器上别的 Nacos 应用混在一起。这里在第一次创建客户端<b>之前</b>把它们指到工作区下的子目录
     * （属性名 {@code JM.LOG.PATH}、{@code JM.SNAPSHOT.PATH}，读 nacos-client 的日志配置和 CacheDirUtil 所得，
     * 日志目录属性也实测有效）。这两个是 JVM 系统属性，已经显式设置过就尊重它；
     * 日志框架在第一个客户端创建时初始化一次，之后再改不再生效。
     */
    public static void redirectClientFiles(String workspace) {
        Path base = Path.of(workspace, "nacos");
        System.getProperties().putIfAbsent("JM.LOG.PATH", base.resolve("logs").toString());
        System.getProperties().putIfAbsent("JM.SNAPSHOT.PATH", base.resolve("snapshot").toString());
    }

    /** 创建客户端并探活；失败时关闭客户端并抛出不含凭证的异常。 */
    public static AiService connect(YokeProperties.Nacos nacos, String workspace) {
        redirectClientFiles(workspace);
        AiService service = null;
        try {
            service = AiFactory.createAiService(clientProperties(nacos));
            probe(service);
            log.info("Nacos 连接正常：{}", nacos.serverAddr());
            return service;
        } catch (NacosException e) {
            shutdownQuietly(service);
            throw new IllegalStateException(failureMessage(nacos, e.getErrCode(), e.getClass().getSimpleName()));
        } catch (RuntimeException e) {
            shutdownQuietly(service);
            throw new IllegalStateException(failureMessage(nacos, -1, e.getClass().getSimpleName()));
        }
    }

    static void probe(AiService service) throws NacosException {
        try {
            service.getAgentCard("yoke-probe-" + UUID.randomUUID());
        } catch (NacosException e) {
            if (e.getErrCode() != NacosException.NOT_FOUND) {
                throw e;
            }
        }
    }

    static String failureMessage(YokeProperties.Nacos nacos, int code, String type) {
        return "无法使用 Nacos 的 AI 通道（" + nacos.serverAddr() + "，错误码 " + code + "，" + type + "）。请确认："
                + "Nacos 版本是 3.x（2.x 没有 AI 模块，会返回 501 或不认识请求）；"
                + "gRPC 端口已开放（主端口 + 1000，默认 9848）；开启了鉴权时 yoke.nacos.username / password 正确"
                + "（环境变量 YOKE_NACOS_USERNAME / YOKE_NACOS_PASSWORD）。本地研发先执行 docker compose up -d";
    }

    private static void shutdownQuietly(AiService service) {
        if (Objects.nonNull(service)) {
            try {
                service.shutdown();
            } catch (NacosException | RuntimeException ignored) {
                // 已经在报启动失败，关闭时的异常没有额外信息
            }
        }
    }
}
