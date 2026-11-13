# Yoke 概览

## Yoke 是什么
Yoke 是一个可以直接运行的 Spring Boot 单体 Agent 服务，定位词是 AgentHarness。它基于 Spring Boot 4.1、AgentScope Java 2.0.4、JDK 25 和 Gradle 构建，是单模块的应用，不是发布给别人依赖的库。

## 每次请求新建一个 Agent
Yoke 不使用单例 Agent。每个对话请求都会由 HarnessAgentFactory 新建一个 HarnessAgent，请求结束时关闭它。这样做的原因是 AgentScope 2.0.4 的 HarnessAgent.Builder 不能重复使用：同一个 Builder 多次 build 会不断追加内部中间件。所以工厂只共享不可变的部分，例如模型、存储和配置，Builder 每次都现建。

## 虚拟线程
应用通过 spring.threads.virtual.enabled 一个开关，让 Tomcat 请求线程和 Spring 默认任务线程池都使用虚拟线程，不需要单独配置线程池。
