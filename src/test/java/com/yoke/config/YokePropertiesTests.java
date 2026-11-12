package com.yoke.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                "Yoke", "prompt", 10, null, null, null, new YokeProperties.Compaction(30, 10), List.of(),
                null, List.of());

        CompactionConfig config = factory.compactionConfig();

        assertThat(config.getTriggerMessages()).isEqualTo(30);
        assertThat(config.getKeepMessages()).isEqualTo(10);
        // AgentScope 中 0 表示「按上下文窗口动态计算」，不是关闭；用最大值让 token 阈值实际上不会先触发
        assertThat(config.getTriggerTokens()).isEqualTo(Integer.MAX_VALUE);
        // 0 才改用 keepMessages，默认 -1 是动态 token
        assertThat(config.getKeepTokens()).isZero();
    }

    // ---- 知识库与 MCP 配置 ----

    @Test
    void 不配置任何MCP服务和向量化服务时使用空默认值() {
        YokeProperties properties = bind(required());

        assertThat(properties.mcp().servers()).isEmpty();
        assertThat(properties.knowledge().embedding().enabled()).isFalse();
        assertThat(properties.knowledge().embedding().baseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(properties.knowledge().embedding().protocol()).isEqualTo(YokeProperties.Knowledge.Embedding.Protocol.OPENAI);
    }

    @Test
    void MCP服务按列表绑定_传输方式忽略大小写_超时有默认值() {
        Map<String, String> properties = required();
        properties.put("yoke.mcp.servers[0].name", "docs");
        properties.put("yoke.mcp.servers[0].transport", "Streamable-HTTP");
        properties.put("yoke.mcp.servers[0].url", "https://mcp.example.com/mcp");
        properties.put("yoke.mcp.servers[0].headers.Authorization", "Bearer t");
        properties.put("yoke.mcp.servers[1].name", "legacy");
        properties.put("yoke.mcp.servers[1].transport", "sse");
        properties.put("yoke.mcp.servers[1].url", "http://10.0.0.5:8080/sse");
        properties.put("yoke.mcp.servers[1].timeout", "5s");

        List<YokeProperties.McpServer> servers = bind(properties).mcp().servers();

        assertThat(servers).extracting(YokeProperties.McpServer::name).containsExactly("docs", "legacy");
        assertThat(servers.get(0).transport()).isEqualTo("streamable-http");
        assertThat(servers.get(0).timeout()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(servers.get(0).headers()).containsEntry("Authorization", "Bearer t");
        assertThat(servers.get(1).timeout()).isEqualTo(java.time.Duration.ofSeconds(5));
    }

    @Test
    void 配置stdio传输时绑定失败_报错说明原因() {
        Map<String, String> properties = required();
        properties.put("yoke.mcp.servers[0].name", "local-fs");
        properties.put("yoke.mcp.servers[0].transport", "stdio");
        properties.put("yoke.mcp.servers[0].url", "http://unused");

        assertThatThrownBy(() -> bind(properties))
                .hasStackTraceContaining("stdio").hasStackTraceContaining("只支持 HTTP 传输")
                .hasStackTraceContaining("local-fs");
    }

    @Test
    void 未知的传输方式和缺失的传输方式同样被拒绝() {
        assertThatThrownBy(() -> new YokeProperties.McpServer("x", "websocket", "http://h", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("只支持 HTTP 传输");
        assertThatThrownBy(() -> new YokeProperties.McpServer("x", null, "http://h", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new YokeProperties.McpServer("x", "  ", "http://h", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 地址必须是http或https() {
        assertThatThrownBy(() -> new YokeProperties.McpServer("x", "sse", "file:///etc/passwd", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http://");
        assertThatThrownBy(() -> new YokeProperties.McpServer("x", "sse", "npx -y some-server", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 服务名不能重复() {
        YokeProperties.McpServer one = new YokeProperties.McpServer("same", "sse", "http://a/sse", null, null);
        YokeProperties.McpServer two = new YokeProperties.McpServer("same", "sse", "http://b/sse", null, null);

        assertThatThrownBy(() -> new YokeProperties.Mcp(List.of(one, two)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能重复");
    }

    @Test
    void MCP服务的toString不泄漏地址里的令牌和请求头的值() {
        YokeProperties.McpServer server = new YokeProperties.McpServer("docs", "sse",
                "https://mcp.example.com:8443/sse?token=query-secret", null, Map.of("Authorization", "Bearer header-secret"));

        String text = server.toString();

        assertThat(text).contains("docs").contains("https://mcp.example.com:8443").contains("Authorization");
        assertThat(text).doesNotContain("query-secret").doesNotContain("header-secret");
    }

    @Test
    void 向量化配置的toString不泄漏密钥() {
        YokeProperties.Knowledge.Embedding embedding = new YokeProperties.Knowledge.Embedding(
                "text-embedding-3-small", "https://api.openai.com/v1", "sk-very-secret", 512,
                YokeProperties.Knowledge.Embedding.Protocol.OPENAI);

        assertThat(embedding.toString()).contains("text-embedding-3-small").doesNotContain("sk-very-secret");
        assertThat(embedding.enabled()).isTrue();
        assertThat(embedding.toString()).contains("protocol=OPENAI");
        assertThat(new YokeProperties.Knowledge.Embedding("m", "u", " ", null, null).enabled()).isFalse();
        assertThat(new YokeProperties.Knowledge.Embedding(null, "u", "k", null, null).enabled()).isFalse();
    }
}
