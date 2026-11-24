package com.yoke.config;

import java.time.Duration;
import java.util.Map;

import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aNacosPropertiesTests {

    private static YokeProperties bind(Map<String, String> extra) {
        Map<String, String> source = new java.util.HashMap<>(Map.of("yoke.workspace", "/tmp/yoke", "yoke.redis.uri", "redis://127.0.0.1:6379"));
        source.putAll(extra);
        return new Binder(new MapConfigurationPropertySource(source)).bind("yoke", YokeProperties.class).get();
    }

    @Test
    void 什么都不配置时A2A服务端_远端和Nacos全部关闭() {
        YokeProperties yoke = bind(Map.of());

        assertThat(yoke.a2a().server().enabled()).isFalse();
        assertThat(yoke.a2a().server().tokenConfigured()).isFalse();
        assertThat(yoke.a2a().remote().configured()).isFalse();
        assertThat(yoke.nacos().a2a().enabled()).isFalse();
        assertThat(yoke.nacos().prompt().enabled()).isFalse();
        assertThat(yoke.nacos().anyEnabled()).isFalse();
        assertThat(yoke.a2a().server().timeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(yoke.a2a().remote().timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void 配置绑定后开关生效() {
        YokeProperties yoke = bind(Map.of("yoke.a2a.server.enabled", "true", "yoke.nacos.prompt.enabled", "true",
                "yoke.a2a.remote.url", "http://other:8080", "yoke.a2a.server.card.skills[0].id", "s1",
                "yoke.a2a.server.card.skills[0].name", "技能一"));

        assertThat(yoke.a2a().server().enabled()).isTrue();
        assertThat(yoke.nacos().anyEnabled()).isTrue();
        assertThat(yoke.a2a().remote().configured()).isTrue();
        assertThat(yoke.a2a().server().card().skills()).extracting(YokeProperties.A2a.Skill::id).containsExactly("s1");
    }

    @Test
    void 敏感配置的toString都脱敏() {
        YokeProperties yoke = bind(Map.of("yoke.a2a.server.token", "server-token-AAA", "yoke.a2a.remote.token", "remote-token-BBB",
                "yoke.a2a.remote.url", "http://other:8080/internal-path?queryParam=should-be-stripped",
                "yoke.nacos.password", "nacos-pass-CCC", "yoke.nacos.username", "nacos-user"));

        String all = yoke.a2a().server() + " " + yoke.a2a().remote() + " " + yoke.nacos() + " " + yoke.a2a() + " " + yoke;

        assertThat(all).doesNotContain("server-token-AAA").doesNotContain("remote-token-BBB").doesNotContain("nacos-pass-CCC")
                .doesNotContain("internal-path").doesNotContain("should-be-stripped");
        assertThat(all).contains("url=http://other:8080").contains("token=***").contains("password=***");
    }

    @Test
    void 远端来源_配了名字且Nacos开启时用Nacos() {
        YokeProperties yoke = bind(Map.of("yoke.a2a.remote.agent-name", "peer", "yoke.nacos.a2a.enabled", "true"));

        assertThat(A2aClientConfiguration.cardSource(yoke)).isEqualTo(A2aClientConfiguration.CardSource.NACOS);
    }

    @Test
    void 远端来源_两个都配且Nacos开启时仍然用Nacos() {
        YokeProperties yoke = bind(Map.of("yoke.a2a.remote.agent-name", "peer", "yoke.a2a.remote.url", "http://x:1",
                "yoke.nacos.a2a.enabled", "true"));

        assertThat(A2aClientConfiguration.cardSource(yoke)).isEqualTo(A2aClientConfiguration.CardSource.NACOS);
    }

    @Test
    void 远端来源_只配url时直连() {
        YokeProperties yoke = bind(Map.of("yoke.a2a.remote.url", "http://x:1"));

        assertThat(A2aClientConfiguration.cardSource(yoke)).isEqualTo(A2aClientConfiguration.CardSource.WELL_KNOWN);
    }

    @Test
    void 远端来源_配了名字但Nacos没开时启动失败而不是悄悄换来源() {
        YokeProperties yoke = bind(Map.of("yoke.a2a.remote.agent-name", "peer", "yoke.a2a.remote.url", "http://x:1"));

        assertThatThrownBy(() -> A2aClientConfiguration.cardSource(yoke))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("yoke.nacos.a2a.enabled");
    }

    @Test
    void 公布的AgentCard一律不声明流式() {
        AgentCard original = new AgentCard.Builder().name("n").description("d").url("http://h/a2a").version("1")
                .capabilities(new io.a2a.spec.AgentCapabilities(true, true, true, java.util.List.of()))
                .defaultInputModes(java.util.List.of("text")).defaultOutputModes(java.util.List.of("text"))
                .skills(java.util.List.of()).build();

        var capabilities = A2aServerConfiguration.publishedCard(original).capabilities();

        assertThat(capabilities.streaming()).isFalse();
        assertThat(capabilities.pushNotifications()).isFalse();
        assertThat(capabilities.stateTransitionHistory()).isFalse();
    }

    @Test
    void 配置了token但太短时启动失败且报错不含token() {
        YokeProperties yoke = bind(Map.of("yoke.a2a.server.token", "short-tok"));

        assertThatThrownBy(() -> A2aServerConfiguration.requireStrongToken(yoke.a2a().server()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("太短")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("short-tok"));
    }

    @Test
    void 足够长的token或没配token都不触发长度检查() {
        A2aServerConfiguration.requireStrongToken(bind(Map.of("yoke.a2a.server.token", "0123456789abcdef")).a2a().server());
        A2aServerConfiguration.requireStrongToken(bind(Map.of()).a2a().server());
    }

    @Test
    void 请求体上限默认256KB且非法值回落() {
        assertThat(bind(Map.of()).a2a().server().maxBodyBytes()).isEqualTo(262144);
        assertThat(bind(Map.of("yoke.a2a.server.max-body-bytes", "-5")).a2a().server().maxBodyBytes()).isEqualTo(262144);
        assertThat(bind(Map.of("yoke.a2a.server.max-body-bytes", "1000")).a2a().server().maxBodyBytes()).isEqualTo(1000);
    }
}
