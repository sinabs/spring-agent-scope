package com.yoke.config;

import io.agentscope.core.ReActAgent;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AgentConfigurationTests {

    @Autowired
    ReActAgent.Builder agentBuilder;

    @Test
    void 每个新建的Agent都带有天气工具() {
        ReActAgent first = agentBuilder.build();
        ReActAgent second = agentBuilder.build();
        try {
            assertThat(first.getToolkit().getToolNames()).containsExactly("get_weather");
            assertThat(second.getToolkit().getToolNames()).containsExactly("get_weather");
        } finally {
            first.close();
            second.close();
        }
    }
}
