package com.yoke.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 用 {@link RecordingModel} 顶替真实模型，测试里 {@code @Import} 即可。 */
@TestConfiguration(proxyBeanMethods = false)
public class StubModelConfiguration {

    @Bean
    RecordingModel recordingModel() {
        return new RecordingModel();
    }
}
