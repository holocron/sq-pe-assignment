package com.sq.caa.rules;

import static com.sq.caa.rules.RuleTestFixtures.batch;
import static com.sq.caa.rules.RuleTestFixtures.card;
import static com.sq.caa.rules.RuleTestFixtures.crypto;
import static com.sq.caa.rules.RuleTestFixtures.evaluate;
import static com.sq.caa.rules.RuleTestFixtures.payment;
import static com.sq.caa.rules.RuleTestFixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The robustness contract: a condition that cannot be evaluated is false and degraded, and nothing
 * the engine is handed can make it throw.
 */
class RuleEvaluatorDegradationTest {

    private static final Instant AT = Instant.parse("2026-08-20T09:30:00Z");

    private final RuleEvaluator evaluator = new RuleEvaluator();

    @Test
    void unknownFieldIsFalseAndDegraded() {
        NodeOutcome outcome = evaluate(
                RuleCondition.of("amont", RuleOperator.GT, new BigDecimal("10")),
                card("100.00", "Completed", AT, "SHOP", "5411", "Debit", true, null));
        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.notes()).containsExactly("unknown field 'amont'");
        assertThat(outcome.explanation()).contains("<unknown field>");
    }

    @Test
    void nullValueIsFalseAndDegradedForEveryOperatorExceptTheNullChecks() {
        Transaction approved = card("100.00", "Completed", AT, "SHOP", "5411", "Debit", true, null);
        NodeOutcome outcome = evaluate(
                RuleCondition.of("card.decline_reason", RuleOperator.CONTAINS, "funds"), approved);
        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.notes())
                .containsExactly("'card.decline_reason' has no value on at least one transaction");
    }

    @Test
    void typeMismatchIsFalseAndDegraded() {
        Transaction transaction = card("100.00", "Completed", AT, "SHOP", "5411", "Debit", true, null);
        NodeOutcome outcome = evaluate(RuleCondition.of("amount", RuleOperator.GT, "not-a-number"),
                transaction);
        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.notes()).anyMatch(note -> note.contains("expected a number"));
    }

    @Test
    void anOperatorThatDoesNotFitTheValueTypeIsFalseAndDegraded() {
        Transaction transaction = card("100.00", "Completed", AT, "SHOP", "5411", "Debit", true, null);
        NodeOutcome outcome = evaluate(
                RuleCondition.of("card.merchant_name", RuleOperator.GT, "AAA"), transaction);
        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.notes()).anyMatch(note -> note.contains("not defined for the text field"));
    }

    @Test
    void aFieldOfAnotherActivityTypeIsFalseButNotDegraded() {
        // An ALL-scoped rule reaching payment.* on a card transaction is ordinary, not a defect.
        Transaction cardTransaction = card("100.00", "Completed", AT, "SHOP", "5411", "Debit", true, null);
        NodeOutcome outcome = evaluate(
                RuleCondition.of("payment.receiver_bank_country", RuleOperator.EQ, "IR"), cardTransaction);
        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.explanation()).contains("<not on CARD>");
    }

    @Test
    void isNullOnAFieldOfAnotherActivityTypeHolds() {
        Transaction cardTransaction = card("100.00", "Completed", AT, "SHOP", "5411", "Debit", true, null);
        assertThat(evaluate(RuleCondition.of("crypto.exchange_name", RuleOperator.IS_NULL), cardTransaction)
                .matched()).isTrue();
        assertThat(evaluate(RuleCondition.of("crypto.exchange_name", RuleOperator.NOT_NULL), cardTransaction)
                .matched()).isFalse();
    }

    @Test
    void aDetailRowThatIsMissingLeavesItsFieldsNullAndDegraded() {
        Transaction withoutDetail = RuleTestFixtures.transaction(ActivityType.CARD, "100.00", "Completed", AT);
        NodeOutcome outcome = evaluate(
                RuleCondition.of("card.mcc_code", RuleOperator.EQ, "5411"), withoutDetail);
        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.degraded()).isTrue();
    }

    @Test
    void unparseableRuleLogicNeverThrowsAndNeverTriggers() {
        RiskRule broken = rule("Broken rule", RuleScope.ALL, "{ not json at all", "20.00");
        RuleEvaluationResult result = evaluator.evaluate(broken,
                batch(payment("100.00", "Completed", AT, "ACH", "US")));

        assertThat(result.triggered()).isFalse();
        assertThat(result.score()).isEqualByComparingTo("0.00");
        assertThat(result.degraded()).isTrue();
        assertThat(result.explanation()).contains("could not be evaluated");
        assertThat(result.degradationNotes()).isNotEmpty();
    }

    @Test
    void ruleLogicReferencingAnUnknownFieldStillEvaluatesEveryTransaction() {
        RiskRule odd = rule("References a field that no longer exists", RuleScope.ALL,
                "{\"field\":\"card.chip_used\",\"operator\":\"EQ\",\"value\":true}", "10.00");
        RuleEvaluationResult result = evaluator.evaluate(odd, batch(
                card("100.00", "Completed", AT, "SHOP", "5411", "Debit", true, null),
                payment("100.00", "Completed", AT, "ACH", "US")));

        assertThat(result.evaluatedTransactionCount()).isEqualTo(2);
        assertThat(result.triggered()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationNotes()).contains("unknown field 'card.chip_used'");
    }

    @Test
    void aRuleReadingAnotherActivityTypesFieldsIsReportedAsDegradedNotAsQuietlyClean() {
        // Refused on write, but a rule already in the table must not spend its life reporting
        // "did not trigger", which reads exactly like a customer with nothing to find.
        RiskRule misScoped = rule("Card rule reading payment fields", RuleScope.CARD,
                "{\"field\":\"payment.receiver_bank_country\",\"operator\":\"EQ\",\"value\":\"IR\"}",
                "20.00");

        RuleEvaluationResult result = evaluator.evaluate(misScoped, batch(
                card("100.00", "Completed", AT, "SHOP", "5411", "Debit", true, null),
                payment("100.00", "Completed", AT, "SWIFT", "IR")));

        assertThat(result.triggered()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationNotes()).anyMatch(note ->
                note.contains("'payment.receiver_bank_country' exists only on PAYMENT activity")
                        && note.contains("can never resolve on a CARD rule"));
    }

    @Test
    void anAllScopedRuleReachingIntoOneActivityTypeStaysClean() {
        RiskRule allScoped = rule("Sanctioned beneficiary", RuleScope.ALL,
                "{\"field\":\"payment.receiver_bank_country\",\"operator\":\"EQ\",\"value\":\"IR\"}",
                "20.00");

        RuleEvaluationResult result = evaluator.evaluate(allScoped, batch(
                card("100.00", "Completed", AT, "SHOP", "5411", "Debit", true, null),
                payment("100.00", "Completed", AT, "SWIFT", "IR")));

        assertThat(result.triggered()).isTrue();
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void evaluationOfEveryOperatorAgainstEveryFieldNeverThrows() {
        EvaluationBatch fixture = batch(
                card("100.00", "Completed", AT, "SHOP", "5411", "Debit", true, null),
                payment("100.00", "Failed", AT, "SWIFT", "IR"),
                crypto("100.00", "Completed", AT, "XMR", null, "wallet-x"));

        assertThatCode(() -> {
            for (FieldDefinition definition : FieldCatalog.entries()) {
                for (RuleOperator operator : RuleOperator.values()) {
                    for (Object value : List.of("junk", new BigDecimal("1"), Boolean.TRUE,
                            List.of("a", "b"), List.of(new BigDecimal("1"), new BigDecimal("2")))) {
                        RuleCondition condition = RuleCondition.of(definition.field(), operator,
                                operator.isNullCheck() ? null : value);
                        evaluator.evaluate(condition, RuleScope.ALL, fixture);
                    }
                }
            }
        }).doesNotThrowAnyException();
    }
}
