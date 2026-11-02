package com.yoke.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class YokePropertiesTests {

    private static YokeProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bind("yoke", YokeProperties.class).get();
    }

    private static Map<String, String> required() {
        Map<String, String> properties = new HashMap<>();
        properties.put("yoke.redis.uri", "redis://127.0.0.1:6379");
        properties.put("yoke.workspace", "/tmp/yoke");
        return properties;
    }

    @Test
    void 压缩配置缺省时使用默认值() {
        YokeProperties.Compaction compaction = bind(required()).compaction();

        assertThat(compaction.triggerMessages()).isEqualTo(30);
        assertThat(compaction.keepMessages()).isEqualTo(10);
    }

    @Test
    void 压缩配置合法时按配置生效() {
        Map<String, String> properties = required();
        properties.put("yoke.compaction.trigger-messages", "50");
        properties.put("yoke.compaction.keep-messages", "20");

        YokeProperties.Compaction compaction = bind(properties).compaction();

        assertThat(compaction.triggerMessages()).isEqualTo(50);
        assertThat(compaction.keepMessages()).isEqualTo(20);
    }

    @Test
    void 触发条数小于等于0时回落到默认值() {
        assertThat(new YokeProperties.Compaction(0, 10)).isEqualTo(new YokeProperties.Compaction(30, 10));
        assertThat(new YokeProperties.Compaction(-5, 3)).isEqualTo(new YokeProperties.Compaction(30, 10));
    }

    @Test
    void 保留条数小于等于0时回落到默认值() {
        assertThat(new YokeProperties.Compaction(40, 0)).isEqualTo(new YokeProperties.Compaction(30, 10));
        assertThat(new YokeProperties.Compaction(40, -1)).isEqualTo(new YokeProperties.Compaction(30, 10));
    }

    @Test
    void 保留条数不小于触发条数时也回落到默认值() {
        assertThat(new YokeProperties.Compaction(10, 10)).isEqualTo(new YokeProperties.Compaction(30, 10));
        assertThat(new YokeProperties.Compaction(10, 20)).isEqualTo(new YokeProperties.Compaction(30, 10));
    }

    @Test
    void 配置里的非法值经过绑定也会回落() {
        Map<String, String> properties = required();
        properties.put("yoke.compaction.trigger-messages", "-1");
        properties.put("yoke.compaction.keep-messages", "0");

        assertThat(bind(properties).compaction()).isEqualTo(new YokeProperties.Compaction(30, 10));
    }

    @Test
    void Redis键前缀默认是yoke冒号且可以覆盖() {
        assertThat(bind(required()).redis().keyPrefix()).isEqualTo("yoke:");

        Map<String, String> properties = required();
        properties.put("yoke.redis.key-prefix", "demo:");
        assertThat(bind(properties).redis().keyPrefix()).isEqualTo("demo:");
    }

    @Test
    void 只按条数触发压缩且token阈值不会先触发() {
        HarnessAgentFactory factory = new HarnessAgentFactory(
                "Yoke", "prompt", 10, null, null, null, null, new YokeProperties.Compaction(30, 10), List.of());

        CompactionConfig config = factory.compactionConfig();

        assertThat(config.getTriggerMessages()).isEqualTo(30);
        assertThat(config.getKeepMessages()).isEqualTo(10);
        // 0 在 2.0.4 里是「按上下文窗口动态计算」，不是关闭；用最大值让 token 阈值实际上不会先触发
        assertThat(config.getTriggerTokens()).isEqualTo(Integer.MAX_VALUE);
        // 0 才改用 keepMessages，默认 -1 是动态 token
        assertThat(config.getKeepTokens()).isZero();
    }
}
