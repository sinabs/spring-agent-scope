package com.yoke.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiresDockerTests {

    @Test
    void Docker可用时放行() {
        assertThat(RequiresDocker.evaluate(true, true).isDisabled()).isFalse();
        assertThat(RequiresDocker.evaluate(true, false).isDisabled()).isFalse();
    }

    @Test
    void 本地没有Docker时跳过并写明原因() {
        var result = RequiresDocker.evaluate(false, false);

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason()).hasValueSatisfying(reason -> assertThat(reason).contains("Docker 不可用"));
    }

    @Test
    void CI里没有Docker时失败而不是跳过() {
        assertThatThrownBy(() -> RequiresDocker.evaluate(false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CI=true");
    }
}
