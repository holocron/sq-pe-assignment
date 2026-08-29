package com.sq.caa.rules;

import static com.sq.caa.rules.RuleTestFixtures.factsOf;
import static com.sq.caa.rules.RuleTestFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.Customer;
import com.sq.caa.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rendered rule and the evaluated rule must be the same rule.
 *
 * <p>{@link RuleFormatter#describe(RuleNode)} is what the analysis screen shows an operator ("none of
 * 38 transactions satisfied ...") and what the agent is handed as the text of a rule it must reason
 * about. If the rendering ever disagreed with the engine - a lost parenthesis, a {@code NOT} that
 * reads as distributing over its children - a reviewer would sign off on a rule that does something
 * else, and nothing in the stack would report it.
 *
 * <p>So this is not a string test. Every shape below is rendered, the rendering is parsed back as a
 * boolean expression by an independent parser, and its value is compared with the real
 * {@link RuleEvaluator} for <em>every</em> combination of leaf truth values. The two must agree in
 * all cases.
 */
class RuleFormatterTest {

    private static final Instant LATE = Instant.parse("2026-08-20T21:00:00Z");
    private static final Instant MIDDAY = Instant.parse("2026-08-20T10:00:00Z");

    /** Three leaves over three independent fields, so each can be made true or false on its own. */
    private static final RuleCondition A =
            RuleCondition.of("amount", RuleOperator.GT, new BigDecimal("100"));
    private static final RuleCondition B =
            RuleCondition.of("hour_of_day", RuleOperator.GTE, new BigDecimal("20"));
    private static final RuleCondition C =
            RuleCondition.of("customer.age", RuleOperator.GT, new BigDecimal("40"));

    private final RuleEvaluator evaluator = new RuleEvaluator();

    private static RuleGroup group(LogicalOp op, RuleNode... children) {
        return new RuleGroup(op, Arrays.asList(children));
    }

    private static List<RuleNode> shapes() {
        return List.of(
                A,
                group(LogicalOp.AND, A, B),
                group(LogicalOp.OR, A, B),
                group(LogicalOp.NOT, A),
                group(LogicalOp.NOT, A, B),
                group(LogicalOp.AND, A),
                group(LogicalOp.AND, A, group(LogicalOp.OR, B, C)),
                group(LogicalOp.OR, group(LogicalOp.AND, A, B), C),
                group(LogicalOp.AND, group(LogicalOp.OR, A, B), group(LogicalOp.NOT, C)),
                group(LogicalOp.OR, group(LogicalOp.NOT, A), group(LogicalOp.AND, B, C)),
                group(LogicalOp.NOT, group(LogicalOp.OR, A, B)),
                group(LogicalOp.AND, group(LogicalOp.NOT, A), group(LogicalOp.OR, B, group(LogicalOp.NOT, C))),
                group(LogicalOp.AND, A, B, C),
                group(LogicalOp.OR, A, group(LogicalOp.AND, B, group(LogicalOp.NOT, C))));
    }

    /** Facts on which A, B and C hold exactly as asked. */
    private TransactionFacts factsFor(boolean a, boolean b, boolean c) {
        Customer customer = RuleTestFixtures.customer("US", c ? 50 : 30);
        Transaction transaction = payment(a ? "500.00" : "50.00", "Completed", b ? LATE : MIDDAY,
                "ACH", "US");
        return factsOf(customer, transaction);
    }

    @Test
    @DisplayName("the leaves really are independently controllable, or the truth table proves nothing")
    void leavesAreIndependent() {
        for (boolean a : new boolean[] {false, true}) {
            for (boolean b : new boolean[] {false, true}) {
                for (boolean c : new boolean[] {false, true}) {
                    TransactionFacts facts = factsFor(a, b, c);
                    assertThat(evaluator.evaluateNode(A, facts).matched()).as("A(%s,%s,%s)", a, b, c)
                            .isEqualTo(a);
                    assertThat(evaluator.evaluateNode(B, facts).matched()).as("B(%s,%s,%s)", a, b, c)
                            .isEqualTo(b);
                    assertThat(evaluator.evaluateNode(C, facts).matched()).as("C(%s,%s,%s)", a, b, c)
                            .isEqualTo(c);
                    assertThat(evaluator.evaluateNode(A, facts).degraded()).isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("every rendered shape evaluates exactly like the shape itself, on every input")
    void renderingNeverDisagreesWithEvaluation() {
        for (RuleNode shape : shapes()) {
            String rendered = RuleFormatter.describe(shape);
            for (boolean a : new boolean[] {false, true}) {
                for (boolean b : new boolean[] {false, true}) {
                    for (boolean c : new boolean[] {false, true}) {
                        boolean evaluated = evaluator.evaluateNode(shape, factsFor(a, b, c)).matched();
                        boolean asDescribed = BooleanText.evaluate(rendered, truths(a, b, c));

                        assertThat(asDescribed)
                                .as("'%s' with A=%s B=%s C=%s: the description says %s, the engine says %s",
                                        rendered, a, b, c, asDescribed, evaluated)
                                .isEqualTo(evaluated);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a NOT over several children reads as NOT of their conjunction, which is what it is")
    void multiChildNotIsRenderedAsNotOfAnAnd() {
        assertThat(RuleFormatter.describe(group(LogicalOp.NOT, A, B)))
                .isEqualTo("NOT (amount GT 100 AND hour_of_day GTE 20)");
    }

    @Test
    @DisplayName("nested groups keep their brackets, so precedence can never be misread")
    void nestingIsAlwaysParenthesised() {
        assertThat(RuleFormatter.describe(group(LogicalOp.AND, A, group(LogicalOp.OR, B, C))))
                .isEqualTo("(amount GT 100 AND (hour_of_day GTE 20 OR customer.age GT 40))");
    }

    @Test
    @DisplayName("a blank operand stays visible in the rendering instead of vanishing")
    void blankOperandsAreQuoted() {
        assertThat(RuleFormatter.describe(RuleCondition.of("card.merchant_name",
                RuleOperator.CONTAINS, "   "))).isEqualTo("card.merchant_name CONTAINS '   '");
    }

    private static Map<String, Boolean> truths(boolean a, boolean b, boolean c) {
        Map<String, Boolean> truths = new LinkedHashMap<>();
        truths.put(RuleFormatter.describeCondition(A), a);
        truths.put(RuleFormatter.describeCondition(B), b);
        truths.put(RuleFormatter.describeCondition(C), c);
        return truths;
    }

    /**
     * Reads back what {@link RuleFormatter} wrote, with no knowledge of the DSL: leaves are replaced
     * by their truth value and the remaining {@code AND} / {@code OR} / {@code NOT} / bracket
     * structure is evaluated. Mixing operators inside one bracket is rejected outright - if the
     * formatter ever emitted {@code a AND b OR c} the reader could not tell what was meant, and
     * neither could an operator.
     */
    private static final class BooleanText {

        private final List<String> tokens;
        private int index;

        private BooleanText(List<String> tokens) {
            this.tokens = tokens;
        }

        static boolean evaluate(String rendered, Map<String, Boolean> truths) {
            String reduced = rendered;
            for (Map.Entry<String, Boolean> leaf : truths.entrySet()) {
                reduced = reduced.replace(leaf.getKey(), leaf.getValue() ? "T" : "F");
            }
            BooleanText reader = new BooleanText(tokenise(reduced));
            boolean value = reader.expression();
            if (reader.index != reader.tokens.size()) {
                throw new AssertionError("unconsumed tokens in '" + rendered + "'");
            }
            return value;
        }

        private static List<String> tokenise(String text) {
            List<String> tokens = new ArrayList<>();
            for (String raw : text.replace("(", " ( ").replace(")", " ) ").trim().split("\\s+")) {
                if (!raw.isEmpty()) {
                    tokens.add(raw);
                }
            }
            return tokens;
        }

        private boolean expression() {
            String token = next();
            return switch (token) {
                case "NOT" -> !expression();
                case "(" -> bracketed();
                case "T" -> true;
                case "F" -> false;
                default -> throw new AssertionError("unexpected token '" + token + "'");
            };
        }

        private boolean bracketed() {
            boolean value = expression();
            String operator = null;
            while (!")".equals(peek())) {
                String next = next();
                if (!"AND".equals(next) && !"OR".equals(next)) {
                    throw new AssertionError("expected AND or OR, found '" + next + "'");
                }
                if (operator == null) {
                    operator = next;
                } else if (!operator.equals(next)) {
                    throw new AssertionError("mixed " + operator + " and " + next + " in one bracket");
                }
                boolean right = expression();
                value = "OR".equals(operator) ? value || right : value && right;
            }
            next();
            return value;
        }

        private String next() {
            if (index >= tokens.size()) {
                throw new AssertionError("ran out of tokens");
            }
            return tokens.get(index++);
        }

        private String peek() {
            return index < tokens.size() ? tokens.get(index) : "";
        }
    }
}
