package com.yoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Yoke 单体 Agent 服务入口。
 */
@SpringBootApplication
public class YokeApplication {

    static void main(String[] args) {
        SpringApplication.run(YokeApplication.class, args);
    }
}
