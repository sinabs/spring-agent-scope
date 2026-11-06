package com.yoke.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Docker 不可用时跳过测试类，并在报告里写明原因，而不是让整个构建失败；
 * 但环境变量 {@code CI=true} 时改为失败，避免 CI 上集成测试被静默跳过。
 */
public class RequiresDocker implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return evaluate(RedisCompose.dockerAvailable(), "true".equalsIgnoreCase(System.getenv("CI")));
    }

    static ConditionEvaluationResult evaluate(boolean dockerAvailable, boolean ci) {
        if (dockerAvailable) {
            return ConditionEvaluationResult.enabled("Docker 可用");
        }
        if (ci) {
            // CI 里静默跳过会让整套集成测试悄悄不跑，宁可让构建失败
            throw new IllegalStateException("CI 环境（CI=true）里 Docker 不可用：需要真实 Redis 的测试不允许被跳过");
        }
        return ConditionEvaluationResult.disabled("Docker 不可用，跳过需要真实 Redis 的测试");
    }
}
