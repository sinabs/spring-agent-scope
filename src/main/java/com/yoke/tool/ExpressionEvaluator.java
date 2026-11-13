package com.yoke.tool;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.function.Supplier;
import cn.hutool.core.util.StrUtil;

/**
 * 四则运算表达式求值器，手写的递归下降解析器。
 *
 * <p>为什么不用脚本引擎或 eval：表达式来自模型，模型的输入又可能来自用户甚至外部网页，
 * 任何能执行代码的求值手段都等于把代码执行权交了出去。这里的文法只有数字、{@code + - * /}、
 * 括号和一元正负号，没有变量、函数、幂运算，也就没有能被滥用的东西。
 *
 * <pre>
 * expr   := term (('+' | '-') term)*
 * term   := factor (('*' | '/') factor)*
 * factor := ('+' | '-') factor | number | '(' expr ')'
 * </pre>
 *
 * <p>防护：输入长度、括号嵌套深度、单个数字的位数都有上限，除数为 0 明确报错；
 * 用 {@link BigDecimal} 避免浮点误差（0.1 + 0.2 得 0.3）。没有幂运算，结果的位数最多是输入位数的量级，
 * 不会像 {@code 9^9^9} 那样撑爆内存。无状态，线程安全；每次求值新建一个解析实例。
 */
public final class ExpressionEvaluator {

    /** 表达式最大长度（字符数）。 */
    public static final int MAX_LENGTH = 200;

    /** 括号最大嵌套深度，避免病态输入把递归栈撑爆。 */
    static final int MAX_DEPTH = 32;

    /** 单个数字字面量最多的字符数（含小数点）。 */
    static final int MAX_NUMBER_LENGTH = 30;

    private static final MathContext DIVISION_PRECISION = MathContext.DECIMAL64;

    private ExpressionEvaluator() {
    }

    /** 表达式不合法（语法、除零、超限）。消息直接给模型看，所以写成清晰的中文。 */
    public static final class InvalidExpressionException extends RuntimeException {

        InvalidExpressionException(String message) {
            super(message);
        }
    }

    /**
     * 求值并格式化为不带科学计数法、不带多余 0 的字符串。
     *
     * @throws InvalidExpressionException 表达式为空、过长、含非法字符、语法错误或除数为 0
     */
    public static String evaluate(String expression) {
        if (StrUtil.isBlank(expression)) {
            throw new InvalidExpressionException("表达式不能为空");
        }
        if (expression.length() > MAX_LENGTH) {
            throw new InvalidExpressionException("表达式太长（最多 " + MAX_LENGTH + " 个字符）");
        }
        BigDecimal result = new Parser(expression).parse();
        BigDecimal normalized = result.signum() == 0 ? BigDecimal.ZERO : result.stripTrailingZeros();
        return normalized.toPlainString();
    }

    private static final class Parser {

        private final String text;
        private int position;
        private int depth;

        Parser(String text) {
            this.text = text;
        }

        BigDecimal parse() {
            BigDecimal value = expression();
            skipSpaces();
            if (position < text.length()) {
                throw unexpected();
            }
            return value;
        }

        private BigDecimal expression() {
            BigDecimal value = term();
            while (true) {
                skipSpaces();
                if (accept('+')) {
                    value = value.add(term());
                } else if (accept('-')) {
                    value = value.subtract(term());
                } else {
                    return value;
                }
            }
        }

        private BigDecimal term() {
            BigDecimal value = factor();
            while (true) {
                skipSpaces();
                if (accept('*')) {
                    value = value.multiply(factor());
                } else if (accept('/')) {
                    BigDecimal divisor = factor();
                    if (divisor.signum() == 0) {
                        throw new InvalidExpressionException("除数不能为 0");
                    }
                    value = value.divide(divisor, DIVISION_PRECISION);
                } else {
                    return value;
                }
            }
        }

        private BigDecimal factor() {
            skipSpaces();
            if (accept('+')) {
                return enter(this::factor);
            }
            if (accept('-')) {
                return enter(this::factor).negate();
            }
            if (accept('(')) {
                BigDecimal inner = enter(this::expression);
                skipSpaces();
                if (!accept(')')) {
                    throw new InvalidExpressionException("缺少右括号 ')'");
                }
                return inner;
            }
            return number();
        }

        /** 每进入一层括号或一元符号就加深一层，超过上限直接拒绝。 */
        private BigDecimal enter(Supplier<BigDecimal> rule) {
            if (++depth > MAX_DEPTH) {
                throw new InvalidExpressionException("括号或正负号嵌套太深（最多 " + MAX_DEPTH + " 层）");
            }
            try {
                return rule.get();
            } finally {
                depth--;
            }
        }

        private BigDecimal number() {
            int start = position;
            boolean seenDot = false;
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c >= '0' && c <= '9') {
                    position++;
                } else if (c == '.' && !seenDot) {
                    seenDot = true;
                    position++;
                } else {
                    break;
                }
            }
            String literal = text.substring(start, position);
            if (literal.isEmpty() || literal.equals(".")) {
                throw unexpected();
            }
            if (literal.length() > MAX_NUMBER_LENGTH) {
                throw new InvalidExpressionException("数字太长（最多 " + MAX_NUMBER_LENGTH + " 位）");
            }
            if (literal.endsWith(".") || literal.startsWith(".")) {
                throw new InvalidExpressionException("数字格式不对：" + literal + "（小数点两侧都要有数字）");
            }
            return new BigDecimal(literal);
        }

        private boolean accept(char expected) {
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (position < text.length() && text.charAt(position) == ' ') {
                position++;
            }
        }

        private InvalidExpressionException unexpected() {
            if (position >= text.length()) {
                return new InvalidExpressionException("表达式不完整，结尾处缺少数字");
            }
            char c = text.charAt(position);
            return new InvalidExpressionException(
                    "第 " + (position + 1) + " 个字符 '" + printable(c) + "' 不合法，只支持数字、+ - * / 和括号");
        }

        private static String printable(char c) {
            return Character.isISOControl(c) ? "\\u" + String.format("%04x", (int) c) : String.valueOf(c);
        }
    }
}
