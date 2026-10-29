package com.yoke;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证虚拟线程真正生效：Tomcat 处理请求的线程、Spring 默认线程池里的线程都必须是虚拟线程。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VirtualThreadsTests {

    /** 仅测试使用：返回处理当前请求的线程是不是虚拟线程。 */
    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class ThreadProbe {

        @GetMapping("/test/thread/virtual")
        boolean isVirtual() {
            return Thread.currentThread().isVirtual();
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    AsyncTaskExecutor applicationTaskExecutor;

    @Test
    void Tomcat使用虚拟线程处理请求() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test/thread/virtual")).build();

        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.body()).isEqualTo("true");
    }

    @Test
    void Spring默认线程池使用虚拟线程() throws Exception {
        boolean virtual = applicationTaskExecutor.submit(() -> Thread.currentThread().isVirtual()).get();

        assertThat(virtual).isTrue();
    }
}
