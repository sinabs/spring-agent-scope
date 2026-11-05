package com.yoke;

import com.yoke.config.HarnessAgentFactory;
import com.yoke.middleware.LoggingMiddleware;
import com.yoke.support.RedisIntegrationTest;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证应用能启动，并且配置文件能驱动官方 starter 创建出模型、工厂能构建出 HarnessAgent（不会真正调用模型）。
 */
@SpringBootTest
@ActiveProfiles("test")
class YokeApplicationTests extends RedisIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void 能创建模型并由工厂构建HarnessAgent() {
        assertThat(context.getBean(Model.class)).isNotNull();
        try (HarnessAgent agent = context.getBean(HarnessAgentFactory.class).create()) {
            assertThat(agent.getName()).isEqualTo("Yoke");
        }
    }

    @Test
    void 日志中间件挂在每个新建的Agent上且不会越积越多() {
        HarnessAgentFactory factory = context.getBean(HarnessAgentFactory.class);
        LoggingMiddleware logging = context.getBean(LoggingMiddleware.class);

        int sizes[] = new int[3];
        for (int i = 0; i < sizes.length; i++) {
            try (HarnessAgent agent = factory.create()) {
                var middlewares = agent.getDelegate().getMiddlewares();
                assertThat(middlewares).containsOnlyOnce(logging);
                sizes[i] = middlewares.size();
            }
        }

        // 如果像 ReActAgent 那样共享同一个 HarnessAgent.Builder，AgentScope 的每次 build 都会多追加一批中间件
        assertThat(sizes[1]).isEqualTo(sizes[0]);
        assertThat(sizes[2]).isEqualTo(sizes[0]);
    }

    @Test
    void 官方的单例Agent已关闭() {
        assertThat(context.getBeansOfType(ReActAgent.class)).isEmpty();
    }
}
