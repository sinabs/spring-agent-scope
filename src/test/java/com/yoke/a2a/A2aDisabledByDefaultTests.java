package com.yoke.a2a;

import com.yoke.support.RedisIntegrationTest;
import com.yoke.support.StubModelConfiguration;
import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全相关的「默认关闭」被测试固化：什么都不配置时，A2A 服务端、远端转发、Nacos 的所有 Bean 和端点都不存在。
 * 以后有人把默认值改成开启，或者把条件注解删掉，这里会失败。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(StubModelConfiguration.class)
class A2aDisabledByDefaultTests extends RedisIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Test
    void 默认没有任何A2A或Nacos相关的Bean() {
        assertThat(context.getBeanNamesForType(AgentScopeA2aServer.class)).isEmpty();
        assertThat(context.getBeanNamesForType(A2aGateway.class)).isEmpty();
        assertThat(context.getBeanNamesForType(RemoteAgentClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AiService.class)).as("Nacos 全关时不应连接 Nacos").isEmpty();
        assertThat(context.getBeanNamesForType(com.yoke.nacos.NacosPromptMiddleware.class)).isEmpty();
    }

    @Test
    void 默认A2A端点和远端转发端点都是404() throws Exception {
        mvc.perform(get("/.well-known/agent-card.json")).andExpect(status().isNotFound());
        mvc.perform(post("/a2a").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/chat/remote").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}
