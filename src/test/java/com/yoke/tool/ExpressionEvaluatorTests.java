package com.yoke.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionEvaluatorTests {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1+2|3",
            "1 + 2 * 3|7",
            "(1 + 2) * 3|9",
            "10 / 4|2.5",
            "0.1 + 0.2|0.3",
            "-3 + 5|2",
            "2 * -3|-6",
            "--4|4",
            "((2))|2",
            "100|100",
            "1/3|0.3333333333333333",
            "7 - 10|-3",
            "1.50 * 2|3",
            "0 * 5|0"
    })
    void 合法表达式按四则运算优先级求值(String expression, String expected) {
        assertThat(ExpressionEvaluator.evaluate(expression)).isEqualTo(expected);
    }

    @Test
    void 除数为0明确报错() {
        assertThatThrownBy(() -> ExpressionEvaluator.evaluate("1 / 0"))
                .isInstanceOf(ExpressionEvaluator.InvalidExpressionException.class)
                .hasMessageContaining("除数不能为 0");
        assertThatThrownBy(() -> ExpressionEvaluator.evaluate("5 / (2 - 2)"))
                .hasMessageContaining("除数不能为 0");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "+", "1 +", "* 2", "(1 + 2", "1 + 2)", "1 2", "1..2", ".5", "5.", "abc", "1 + a",
            "Math.sqrt(4)", "System.exit(0)", "1; 2", "2 ** 3", "2 ^ 3", "1e3", "0x10", "１＋２", "(１)", "1\t+ 2"
    })
    void 非法表达式一律拒绝且不会执行任何代码(String expression) {
        assertThatThrownBy(() -> ExpressionEvaluator.evaluate(expression))
                .isInstanceOf(ExpressionEvaluator.InvalidExpressionException.class);
    }

    @Test
    void null也只是非法表达式() {
        assertThatThrownBy(() -> ExpressionEvaluator.evaluate(null))
                .isInstanceOf(ExpressionEvaluator.InvalidExpressionException.class);
    }

    @Test
    void 超长输入被拒绝() {
        String tooLong = "1+".repeat(ExpressionEvaluator.MAX_LENGTH) + "1";

        assertThatThrownBy(() -> ExpressionEvaluator.evaluate(tooLong))
                .hasMessageContaining("太长");
        // 恰好在上限内的长表达式仍可计算
        String atLimit = "1" + "+1".repeat((ExpressionEvaluator.MAX_LENGTH - 1) / 2);
        assertThat(atLimit.length()).isLessThanOrEqualTo(ExpressionEvaluator.MAX_LENGTH);
        assertThat(ExpressionEvaluator.evaluate(atLimit)).isEqualTo(String.valueOf(1 + (ExpressionEvaluator.MAX_LENGTH - 1) / 2));
    }

    @Test
    void 括号嵌套过深被拒绝而不是栈溢出() {
        String deep = "(".repeat(ExpressionEvaluator.MAX_DEPTH + 1) + "1" + ")".repeat(ExpressionEvaluator.MAX_DEPTH + 1);

        assertThatThrownBy(() -> ExpressionEvaluator.evaluate(deep)).hasMessageContaining("嵌套太深");
        String fine = "(".repeat(ExpressionEvaluator.MAX_DEPTH) + "1" + ")".repeat(ExpressionEvaluator.MAX_DEPTH);
        assertThat(ExpressionEvaluator.evaluate(fine)).isEqualTo("1");
    }

    @Test
    void 连续的一元符号同样受深度限制() {
        String minuses = "-".repeat(ExpressionEvaluator.MAX_DEPTH + 1) + "1";

        assertThatThrownBy(() -> ExpressionEvaluator.evaluate(minuses)).hasMessageContaining("嵌套太深");
    }

    @Test
    void 超长数字被拒绝() {
        String number = "9".repeat(ExpressionEvaluator.MAX_NUMBER_LENGTH + 1);

        assertThatThrownBy(() -> ExpressionEvaluator.evaluate(number + " + 1")).hasMessageContaining("数字太长");
    }

    @Test
    void 大数相乘用高精度不溢出() {
        assertThat(ExpressionEvaluator.evaluate("99999999999 * 99999999999")).isEqualTo("9999999999800000000001");
    }

    @Test
    void 错误位置指向第一个非法字符() {
        assertThatThrownBy(() -> ExpressionEvaluator.evaluate("1 + a"))
                .hasMessageContaining("第 5 个字符 'a'");
    }

    @Test
    void 链式除以极小数结果很大但不会失控() {
        // 12 次除以 1e-10，等于乘以 1e120；输入在长度上限内，结果是 121 位的整数，仍然是精确的十进制文本
        String expression = "1" + "/0.0000000001".repeat(12);
        assertThat(expression.length()).isLessThanOrEqualTo(ExpressionEvaluator.MAX_LENGTH);

        String result = ExpressionEvaluator.evaluate(expression);

        assertThat(result).isEqualTo("1" + "0".repeat(120));
    }

    @Test
    void 链式乘以极小数结果的小数位随输入线性增长_仍能正常输出() {
        // 6 个 25 位小数相乘：小数位数为各因子之和（150 位），输入长度上限保证了它不会无限增长
        String factor = "0.0000000000000000000000001";
        String expression = String.join("*", java.util.Collections.nCopies(6, factor));
        assertThat(expression.length()).isLessThanOrEqualTo(ExpressionEvaluator.MAX_LENGTH);

        String result = ExpressionEvaluator.evaluate(expression);

        assertThat(result).startsWith("0.").endsWith("1").hasSize(2 + 150);
        assertThat(result.chars().filter(c -> c == '1').count()).isEqualTo(1);
    }
}
