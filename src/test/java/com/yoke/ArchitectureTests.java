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

    /** 控制器为了拿到装配好的 Agent，只允许依赖这几个框架类型，其余（Msg、各种事件）一律不行。 */
    private static final Set<String> CONTROLLER_ALLOWED = Set.of(
            "io.agentscope.core.ReActAgent",
            "io.agentscope.core.ReActAgent$Builder",
            "io.agentscope.core.agent.RuntimeContext",
            "io.agentscope.core.agent.RuntimeContext$Builder");

    /** 配置类装配适配器配置所必需的 AG-UI 类型，其余 AG-UI 类型一律不行。 */
    private static final Set<String> CONFIG_AGUI_ALLOWED = Set.of(
            "io.agentscope.core.agui.adapter.AguiAdapterConfig",
            "io.agentscope.core.agui.adapter.AguiAdapterConfig$Builder");

    private static final DescribedPredicate<JavaClass> AGUI_OUTSIDE_CONFIG_ALLOWED_LIST = DescribedPredicate
            .and(resideInAPackage("io.agentscope.core.agui.."),
                    DescribedPredicate.not(new DescribedPredicate<JavaClass>(
                            "the config allowed types " + CONFIG_AGUI_ALLOWED) {
                        @Override
                        public boolean test(JavaClass input) {
                            return CONFIG_AGUI_ALLOWED.contains(input.getName());
                        }
                    }));

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

    @ArchTest
    static final ArchRule 框架的消息和事件类型只能出现在codec里 = noClasses()
            .that().resideOutsideOfPackage("com.yoke.codec..")
            .should().dependOnClassesThat().resideInAnyPackage("io.agentscope.core.message..", "io.agentscope.core.event..");

    @ArchTest
    static final ArchRule AGUI协议类型只能出现在codec和config里 = noClasses()
            .that().resideOutsideOfPackages("com.yoke.codec..", "com.yoke.config..")
            .should().dependOnClassesThat().resideInAPackage("io.agentscope.core.agui..");

    @ArchTest
    static final ArchRule config只能依赖适配器配置这几个AGUI类型 = noClasses()
            .that().resideInAPackage("com.yoke.config..")
            .should().dependOnClassesThat(AGUI_OUTSIDE_CONFIG_ALLOWED_LIST);
}
