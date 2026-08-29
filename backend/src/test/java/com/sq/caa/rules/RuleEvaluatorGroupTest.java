package com.sq.caa.rules;

import static com.sq.caa.rules.RuleTestFixtures.evaluate;
import static com.sq.caa.rules.RuleTestFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** AND / OR / NOT semantics, nesting, and the deliberate absence of short-circuiting. */
class RuleEvaluatorGroupTest {

    private static final Instant AT = Instant.parse("2026-08-20T09:30:00Z");

    private static Transaction sanctionedWire() {
        return payment("15000.00", "Completed", AT, "SWIFT", "IR");
    }

    private static RuleCondition amountGt(String amount) {
        return RuleCondition.of("amount", RuleOperator.GT, new BigDecimal(amount));
    }

    @Test
    void andRequiresEveryChild() {
        Transaction wire = sanctionedWire();
        assertThat(evaluate(RuleGroup.and(amountGt("10000"),
                RuleCondition.of("payment.receiver_bank_country", RuleOperator.IN, List.of("IR", "KP"))),
                wire).matched()).isTrue();
        assertThat(evaluate(RuleGroup.and(amountGt("10000"),
                RuleCondition.of("payment.receiver_bank_country", RuleOperator.IN, List.of("KP"))),
                wire).matched()).isFalse();
    }

    @Test
    void orNeedsOnlyOneChild() {
        Transaction wire = sanctionedWire();
        assertThat(evaluate(RuleGroup.or(amountGt("1000000"),
                RuleCondition.of("payment.payment_method", RuleOperator.EQ, "SWIFT")), wire)
                .matched()).isTrue();
        assertThat(evaluate(RuleGroup.or(amountGt("1000000"),
                RuleCondition.of("payment.payment_method", RuleOperator.EQ, "ACH")), wire)
                .matched()).isFalse();
    }

    @Test
    void notNegatesASingleChild() {
        Transaction wire = sanctionedWire();
        assertThat(evaluate(RuleGroup.not(
                RuleCondition.of("customer.country", RuleOperator.EQ, "US")), wire).matched()).isFalse();
        assertThat(evaluate(RuleGroup.not(
                RuleCondition.of("customer.country", RuleOperator.EQ, "DE")), wire).matched()).isTrue();
    }

    @Test
    void notNegatesTheConjunctionOfSeveralChildren() {
        Transaction wire = sanctionedWire();
        // NOT(amount > 10000 AND method = SWIFT) - both hold, so the negation is false.
        assertThat(evaluate(RuleGroup.not(amountGt("10000"),
                RuleCondition.of("payment.payment_method", RuleOperator.EQ, "SWIFT")), wire)
                .matched()).isFalse();
        // One child fails, so the conjunction fails and the negation holds.
        assertThat(evaluate(RuleGroup.not(amountGt("10000"),
                RuleCondition.of("payment.payment_method", RuleOperator.EQ, "ACH")), wire)
                .matched()).isTrue();
    }

    @Test
    void groupsNestToArbitraryDepth() {
        Transaction wire = sanctionedWire();
        RuleNode node = RuleGroup.and(
                amountGt("10000"),
                RuleGroup.or(
                        RuleCondition.of("payment.receiver_bank_country", RuleOperator.IN,
                                List.of("IR", "KP", "SY", "RU", "AF")),
                        RuleCondition.of("customer.country", RuleOperator.NEQ, "US")),
                RuleGroup.not(RuleCondition.of("status", RuleOperator.EQ, "Reversed")));
        NodeOutcome outcome = evaluate(node, wire);
        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.degraded()).isFalse();
    }

    @Test
    void anAlreadyDecidedAndStillReportsDegradationInLaterBranches() {
        // The first child fails, so a short-circuiting engine would never see the unknown field.
        // This engine evaluates every branch precisely so defects cannot hide behind a false.
        NodeOutcome outcome = evaluate(RuleGroup.and(
                amountGt("999999"),
                RuleCondition.of("payment.receiver_iban", RuleOperator.EQ, "X")), sanctionedWire());
        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.notes()).contains("unknown field 'payment.receiver_iban'");
    }

    @Test
    void anAlreadySatisfiedOrStillReportsDegradation() {
        NodeOutcome outcome = evaluate(RuleGroup.or(
                amountGt("100"),
                RuleCondition.of("payment.receiver_iban", RuleOperator.EQ, "X")), sanctionedWire());
        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.degraded()).isTrue();
    }

    @Test
    void groupExplanationShowsEveryChildOutcome() {
        NodeOutcome outcome = evaluate(RuleGroup.and(
                amountGt("10000"),
                RuleCondition.of("payment.payment_method", RuleOperator.EQ, "SWIFT")), sanctionedWire());
        assertThat(outcome.explanation())
                .isEqualTo("(amount=15000 GT 10000 [true] AND payment.payment_method='SWIFT' EQ 'SWIFT' [true]) [true]");
    }

    @Test
    void notExplanationIsPrefixed() {
        NodeOutcome outcome = evaluate(
                RuleGroup.not(RuleCondition.of("status", RuleOperator.EQ, "Reversed")), sanctionedWire());
        assertThat(outcome.explanation()).startsWith("NOT (status='Completed' EQ 'Reversed' [false])");
    }
}
