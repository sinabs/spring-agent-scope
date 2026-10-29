package com.yoke;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证应用能启动，并且配置文件能驱动官方 starter 创建出模型和 Agent（不会真正调用模型）。
 */
@SpringBootTest
@ActiveProfiles("test")
class YokeApplicationTests {

    @Autowired
    ApplicationContext context;

    @Test
    void 能创建模型并由共享Builder构建Agent() {
        assertThat(context.getBean(Model.class)).isNotNull();
        try (ReActAgent agent = context.getBean(ReActAgent.Builder.class).build()) {
            assertThat(agent.getName()).isEqualTo("Yoke");
        }
    }

    @Test
    void 官方的单例Agent已关闭() {
        assertThat(context.getBeansOfType(ReActAgent.class)).isEmpty();
    }
}
