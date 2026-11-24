package com.yoke;

import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoke.support.RedisIntegrationTest;
import com.yoke.support.StubModelConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可观测端点的暴露面是精确的集合：只有 health、info、metrics、prometheus。
 * 以后多暴露了 env、heapdump、beans 之类敏感端点（或者有人把 include 改成 *），这里会失败。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(StubModelConfiguration.class)
class ActuatorExposureTests extends RedisIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 暴露的端点集合精确等于约定() throws Exception {
        String body = mvc.perform(get("/actuator")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Set<String> exposed = new TreeSet<>();
        json.readTree(body).path("_links").fieldNames().forEachRemaining(exposed::add);
        exposed.remove("self");

        assertThat(exposed).containsExactlyInAnyOrder("health", "health-path", "info", "metrics",
                "metrics-requiredMetricName", "prometheus");
    }

    @Test
    void 敏感端点一律不可访问() throws Exception {
        for (String sensitive : new String[] {"env", "heapdump", "threaddump", "beans", "configprops", "mappings",
                "loggers", "conditions", "caches", "scheduledtasks", "shutdown", "sessions", "httpexchanges"}) {
            mvc.perform(get("/actuator/" + sensitive)).andExpect(status().isNotFound());
        }
    }

    @Test
    void health包含Redis且只报状态不带明细() throws Exception {
        String body = mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode health = json.readTree(body);

        assertThat(health.path("status").asText()).isEqualTo("UP");
        assertThat(health.has("components")).as("show-details=never，不返回组件明细").isFalse();
        // 组件明细被隐藏，所以改为确认 Redis 的指示器确实挂在聚合状态里（DOWN 的行为见 RedisHealthConfigurationTests）
        assertThat(context.getBeansOfType(HealthIndicator.class)).containsKey("redisHealthIndicator");
    }

    @Test
    void info只回显静态的应用名() throws Exception {
        String body = mvc.perform(get("/actuator/info")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).path("app").path("name").asText()).isEqualTo("Yoke");
        assertThat(body).doesNotContain("password").doesNotContain("api-key");
    }

    @Test
    void metrics和prometheus可用且带应用标签() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
        String text = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(text).contains("jvm_memory_used_bytes").contains("application=\"yoke\"");
    }
}
