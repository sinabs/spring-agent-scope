package com.yoke.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import cn.hutool.core.util.StrUtil;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.context.annotation.Condition;

/** 只在配置了远端 A2A Agent（{@code yoke.a2a.remote.url} 或 {@code yoke.a2a.remote.agent-name}）时才生效。 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ConditionalOnRemoteAgent.RemoteConfigured.class)
public @interface ConditionalOnRemoteAgent {

    class RemoteConfigured implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            return StrUtil.isNotBlank(env.getProperty("yoke.a2a.remote.url"))
                    || StrUtil.isNotBlank(env.getProperty("yoke.a2a.remote.agent-name"));
        }
    }
}
