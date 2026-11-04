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
 * 会话 ID 的取值规则：字母、数字、{@code _.-}，1~64 位，不能全是点，避免被用来拼出别的会话的存储位置。
 *
 * <p>作为校验注解使用时，null 和空白放行：同步接口的 {@code sessionId} 可以不传，缺省时由服务端新建会话。
 * 必填的入口（AG-UI 的 {@code threadId}）直接调用 {@link Validator#isSafe}。
 */
@Documented
@Constraint(validatedBy = SafeId.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeId {

    String message() default "必须是 1~64 位的字母、数字或 _.-，且不能全是点";

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
