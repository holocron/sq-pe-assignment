package com.sq.caa.rules;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;

/**
 * Renders rules and values as text.
 *
 * <p>Used for the human-readable explanation attached to every evaluation result, which the UI shows
 * to an operator and the agent quotes as its rationale, so the format is kept terse and stable.
 */
public final class RuleFormatter {

    private RuleFormatter() {
    }

    /** {@code amount GT 10000 AND (customer.country NEQ 'US' OR payment.payment_method EQ 'SWIFT')} */
    public static String describe(RuleNode node) {
        return switch (node) {
            case RuleCondition condition -> describeCondition(condition);
            case RuleGroup group -> describeGroup(group);
        };
    }

    private static String describeGroup(RuleGroup group) {
        List<RuleNode> children = group.conditions();
        String body;
        if (children.size() == 1) {
            body = describe(children.get(0));
        } else {
            StringJoiner joiner = new StringJoiner(" " + joinWord(group.op()) + " ", "(", ")");
            for (RuleNode child : children) {
                joiner.add(describe(child));
            }
            body = joiner.toString();
        }
        return group.op() == LogicalOp.NOT ? "NOT " + parenthesise(body) : body;
    }

    private static String joinWord(LogicalOp op) {
        return op == LogicalOp.OR ? "OR" : "AND";
    }

    private static String parenthesise(String body) {
        return body.startsWith("(") ? body : "(" + body + ")";
    }

    public static String describeCondition(RuleCondition condition) {
        if (condition.operator().isNullCheck()) {
            return condition.field() + " " + condition.operator().name();
        }
        return condition.field() + " " + condition.operator().name() + " " + value(condition.value());
    }

    /** Renders any operand or resolved field value in a compact, unambiguous way. */
    public static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof List<?> list) {
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            for (Object element : list) {
                joiner.add(value(element));
            }
            return joiner.toString();
        }
        return "'" + value + "'";
    }

    /** Cuts an explanation down to a size that is safe to store and to show in a table cell. */
    public static String abbreviate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }
}
