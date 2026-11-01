package com.yoke;

import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 守住包边界：框架（AgentScope）的类型不能外泄到对外契约里。
 * 单模块没有编译期的模块隔离，所以用测试表达依赖方向，违反时构建直接失败。
 */
@AnalyzeClasses(packages = "com.yoke", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    /**
     * 控制器为了使用每请求新建的 Agent，只允许依赖这几个框架类型，其余（Msg、各种事件）一律不行。
     * HarnessAgent 取代了第 01 轮的 ReActAgent（Builder 已由 HarnessAgentFactory 封装，不再出现在控制器里）。
     */
    private static final Set<String> CONTROLLER_ALLOWED = Set.of(
            "io.agentscope.harness.agent.HarnessAgent",
            "io.agentscope.core.agent.RuntimeContext",
            "io.agentscope.core.agent.RuntimeContext$Builder");

    private static final DescribedPredicate<JavaClass> FRAMEWORK_OUTSIDE_ALLOWED_LIST = DescribedPredicate
            .and(resideInAPackage("io.agentscope.."),
                    DescribedPredicate.not(new DescribedPredicate<JavaClass>(
                            "the controller allowed types " + CONTROLLER_ALLOWED) {
                        @Override
                        public boolean test(JavaClass input) {
                            return CONTROLLER_ALLOWED.contains(input.getName());
                        }
                    }));

    @ArchTest
    static final ArchRule 对外事件和请求响应模型不依赖框架 = noClasses().that().resideInAPackage("com.yoke.model..")
            .should().dependOnClassesThat().resideInAPackage("io.agentscope..");

    @ArchTest
    static final ArchRule HTTP契约接口不依赖框架 = noClasses().that().resideInAPackage("com.yoke.api..")
            .should().dependOnClassesThat().resideInAPackage("io.agentscope..");

    @ArchTest
    static final ArchRule 控制器只能依赖Agent装配相关的框架类型 = noClasses().that().resideInAPackage("com.yoke.controller..")
            .should().dependOnClassesThat(FRAMEWORK_OUTSIDE_ALLOWED_LIST);

    /**
     * 消息和事件类型只允许出现在 codec，以及实现框架 SPI 的 middleware 和 a2a.runner：
     * MiddlewareBase 的钩子签名本身就是 Flux&lt;AgentEvent&gt;，A2A 的 AgentRunner 接口签名是
     * {@code Flux<AgentEvent> streamEvents(List<Msg>, ...)}，实现它们不可避免会碰到这些类型。
     */
    @ArchTest
    static final ArchRule 框架的消息和事件类型只能出现在codec和middleware里 = noClasses()
            .that().resideOutsideOfPackages("com.yoke.codec..", "com.yoke.middleware..", "com.yoke.a2a.runner..")
            .should().dependOnClassesThat().resideInAnyPackage("io.agentscope.core.message..", "io.agentscope.core.event..");

    /**
     * 检索端口是应用层自己的接口：不依赖 AgentScope（2.0.4 里框架的 rag 包已标记为将移除），
     * 以后换向量库、换检索算法都不影响 Agent 和工具。向量化客户端、关键词检索、融合与降级都在这个包里。
     */
    @ArchTest
    static final ArchRule 知识库检索包不依赖框架 = noClasses().that().resideInAPackage("com.yoke.knowledge..")
            .should().dependOnClassesThat().resideInAPackage("io.agentscope..");

    @ArchTest
    static final ArchRule 知识库检索包不反向依赖工具_配置和接口层 = noClasses().that().resideInAPackage("com.yoke.knowledge..")
            .should().dependOnClassesThat().resideInAnyPackage("com.yoke.tool..", "com.yoke.mcp..", "com.yoke.config..",
                    "com.yoke.controller..", "com.yoke.api..");

    /** 工具类只允许用框架的工具注解和 Toolkit 一族（io.agentscope.core.tool），不碰 Agent、模型、消息。 */
    @ArchTest
    static final ArchRule 工具类只依赖框架的tool包 = noClasses().that().resideInAPackage("com.yoke.tool..")
            .should().dependOnClassesThat(DescribedPredicate.and(resideInAPackage("io.agentscope.."),
                    DescribedPredicate.not(resideInAPackage("io.agentscope.core.tool.."))));

    /** MCP 连接只用框架的 tool 包（McpClientBuilder、Toolkit 都在那里）。 */
    @ArchTest
    static final ArchRule MCP连接只依赖框架的tool包 = noClasses().that().resideInAPackage("com.yoke.mcp..")
            .should().dependOnClassesThat(DescribedPredicate.and(resideInAPackage("io.agentscope.."),
                    DescribedPredicate.not(resideInAPackage("io.agentscope.core.tool.."))));

    /** A2A 的网关和客户端（runner 除外）只用 A2A 协议类型和官方 A2A 扩展，不碰 Agent、模型、消息。 */
    @ArchTest
    static final ArchRule A2A包只依赖A2A协议和扩展 = noClasses()
            .that().resideInAPackage("com.yoke.a2a..").and().resideOutsideOfPackage("com.yoke.a2a.runner..")
            .should().dependOnClassesThat(DescribedPredicate.and(resideInAPackage("io.agentscope.."),
                    DescribedPredicate.not(resideInAPackage("io.agentscope.core.a2a.."))));

    /**
     * runner 是 A2A AgentRunner 的实现，只能用下面这几个框架类型：SPI 本身、每请求新建的 HarnessAgent、
     * 运行时上下文、最终结果事件和消息（改写成纯文本回复要用）。
     */
    private static final Set<String> RUNNER_ALLOWED = Set.of(
            "io.agentscope.core.a2a.server.executor.runner.AgentRunner",
            "io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions",
            "io.agentscope.harness.agent.HarnessAgent",
            "io.agentscope.core.agent.RuntimeContext",
            "io.agentscope.core.agent.RuntimeContext$Builder",
            "io.agentscope.core.event.AgentEvent",
            "io.agentscope.core.event.AgentResultEvent",
            "io.agentscope.core.message.Msg",
            "io.agentscope.core.message.Msg$Builder",
            "io.agentscope.core.message.MsgRole");

    @ArchTest
    static final ArchRule A2A的runner只依赖白名单里的框架类型 = noClasses().that().resideInAPackage("com.yoke.a2a.runner..")
            .should().dependOnClassesThat(DescribedPredicate.and(resideInAPackage("io.agentscope.."),
                    DescribedPredicate.not(new DescribedPredicate<JavaClass>("the runner allowed types " + RUNNER_ALLOWED) {
                        @Override
                        public boolean test(JavaClass input) {
                            return RUNNER_ALLOWED.contains(input.getName());
                        }
                    })));

    /** Nacos 接线：Prompt 中间件实现 MiddlewareBase（要用 Agent、RuntimeContext），注册用官方的 nacos-a2a，其余一概不碰。 */
    private static final Set<String> NACOS_ALLOWED = Set.of(
            "io.agentscope.core.middleware.MiddlewareBase",
            "io.agentscope.core.agent.Agent",
            "io.agentscope.core.agent.RuntimeContext");

    @ArchTest
    static final ArchRule Nacos包只依赖中间件SPI和官方nacos_a2a = noClasses().that().resideInAPackage("com.yoke.nacos..")
            .should().dependOnClassesThat(DescribedPredicate.and(resideInAPackage("io.agentscope.."),
                    DescribedPredicate.not(resideInAPackage("io.agentscope.core.nacos.a2a..")),
                    DescribedPredicate.not(new DescribedPredicate<JavaClass>("the nacos allowed types " + NACOS_ALLOWED) {
                        @Override
                        public boolean test(JavaClass input) {
                            return NACOS_ALLOWED.contains(input.getName());
                        }
                    })));

    @ArchTest
    static final ArchRule A2A和Nacos包不反向依赖接口层 = noClasses()
            .that().resideInAnyPackage("com.yoke.a2a..", "com.yoke.nacos..")
            .should().dependOnClassesThat().resideInAnyPackage("com.yoke.controller..", "com.yoke.api..");
}
