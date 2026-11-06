package com.yoke.model;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * 用户标识和会话标识的格式约束：1 到 64 个字母、数字、下划线、点、短横线，且不能全是点（排除 {@code .}、{@code ..}）。
 *
 * <p>框架把槽位拼成 {@code userId + "/" + sessionId}，允许斜杠会让 (a, "b/c") 与 ("a/b", c) 撞到同一个槽位；
 * userId 还会成为工作区目录名，{@code ..} 会越出目录。
 *
 * <p>作为校验注解使用时，null 和空白放行：由服务端补默认值或新建会话。
 * 必填的入口（AG-UI 的 {@code threadId}）直接调用 {@link Validator#isSafe}。
 */
@Documented
@Constraint(validatedBy = SafeId.Validator.class)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeId {

    String message() default "只能包含字母、数字、下划线、点和短横线，长度 1 到 64，且不能全是点";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<SafeId, String> {

        private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

        private static final Pattern ALL_DOTS = Pattern.compile("\\.+");

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return StrUtil.isBlank(value) || isSafe(value);
        }

        /** 非 null 且符合规则。 */
        public static boolean isSafe(String id) {
            return Objects.nonNull(id) && SAFE_ID.matcher(id).matches() && !ALL_DOTS.matcher(id).matches();
        }
    }
}
