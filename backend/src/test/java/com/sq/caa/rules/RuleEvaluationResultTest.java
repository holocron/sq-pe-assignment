package com.sq.caa.rules;

import static com.sq.caa.rules.RuleTestFixtures.batch;
import static com.sq.caa.rules.RuleTestFixtures.card;
import static com.sq.caa.rules.RuleTestFixtures.crypto;
import static com.sq.caa.rules.RuleTestFixtures.customer;
import static com.sq.caa.rules.RuleTestFixtures.payment;
import static com.sq.caa.rules.RuleTestFixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.domain.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rule-level results: scope filtering, scoring, matched ids, samples and the explanation. */
class RuleEvaluationResultTest {

    private static final Instant T = Instant.parse("2026-08-20T12:00:00Z");
    private static final String LARGE_AMOUNT =
            "{\"field\":\"amount\",\"operator\":\"GT\",\"value\":1000}";

    private final RuleEvaluator evaluator = new RuleEvaluator();

    @Test
    void onlyTransactionsInTheRuleScopeAreEvaluated() {
        EvaluationBatch fixture = batch(
                card("5000.00", "Completed", T, "SHOP", "5411", "Credit", true, null),
                payment("5000.00", "Completed", T.minus(Duration.ofHours(1)), "SWIFT", "IR"),
                crypto("5000.00", "Completed", T.minus(Duration.ofHours(2)), "XMR", null, "wallet-x"));

        RuleEvaluationResult cardOnly = evaluator.evaluate(
                rule("Large card spend", RuleScope.CARD, LARGE_AMOUNT, "15.00"), fixture);
        assertThat(cardOnly.evaluatedTransactionCount()).isEqualTo(1);
        assertThat(cardOnly.matchedCount()).isEqualTo(1);

        RuleEvaluationResult everywhere = evaluator.evaluate(
                rule("Large anything", RuleScope.ALL, LARGE_AMOUNT, "15.00"), fixture);
        assertThat(everywhere.evaluatedTransactionCount()).isEqualTo(3);
        assertThat(everywhere.matchedCount()).isEqualTo(3);
    }

    @Test
    void scoreIsTheWeightOnceAndOnlyOnce() {
        EvaluationBatch fixture = batch(
                payment("5000.00", "Completed", T, "SWIFT", "IR"),
                payment("6000.00", "Completed", T.minus(Duration.ofHours(1)), "SWIFT", "IR"),
                payment("7000.00", "Completed", T.minus(Duration.ofHours(2)), "SWIFT", "IR"));

        RiskRule ruleDefinition = rule("Large payment", RuleScope.PAYMENT, LARGE_AMOUNT, "25.00");
        RuleEvaluationResult result = evaluator.evaluate(ruleDefinition, fixture);

        assertThat(result.triggered()).isTrue();
        assertThat(result.matchedCount()).isEqualTo(3);
        assertThat(result.score()).isEqualByComparingTo("25.00");
        assertThat(result.weight()).isEqualByComparingTo("25.00");
        assertThat(result.ruleId()).isEqualTo(ruleDefinition.getRuleId());
        assertThat(result.ruleName()).isEqualTo("Large payment");
        assertThat(result.appliesTo()).isEqualTo(RuleScope.PAYMENT);
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void aRuleThatDoesNotMatchScoresZero() {
        RuleEvaluationResult result = evaluator.evaluate(
                rule("Large payment", RuleScope.PAYMENT, LARGE_AMOUNT, "25.00"),
                batch(payment("10.00", "Completed", T, "ACH", "US")));

        assertThat(result.triggered()).isFalse();
        assertThat(result.score()).isEqualByComparingTo("0.00");
        assertThat(result.matchedTransactionIds()).isEmpty();
        assertThat(result.explanation()).contains("did not trigger")
                .contains("amount GT 1000");
    }

    @Test
    void matchedIdsAreNewestFirstAndSamplesAreLargestFirst() {
        Transaction newestSmall = payment("1500.00", "Completed", T, "ACH", "US");
        Transaction olderLarge = payment("9000.00", "Completed", T.minus(Duration.ofDays(1)), "ACH", "US");
        EvaluationBatch fixture = batch(newestSmall, olderLarge);

        RuleEvaluationResult result = evaluator.evaluate(
                rule("Large payment", RuleScope.PAYMENT, LARGE_AMOUNT, "25.00"), fixture);

        assertThat(result.matchedTransactionIds())
                .containsExactly(newestSmall.getTransactionId(), olderLarge.getTransactionId());
        assertThat(result.sampleMatches()).extracting(RuleMatch::transactionId)
                .containsExactly(olderLarge.getTransactionId(), newestSmall.getTransactionId());
    }

    @Test
    void samplesAreCappedButTheMatchedIdsAreNot() {
        Transaction[] transactions = new Transaction[8];
        for (int i = 0; i < transactions.length; i++) {
            transactions[i] = payment("2000.00", "Completed", T.minus(Duration.ofHours(i)), "ACH", "US");
        }
        RuleEvaluationResult result = evaluator.evaluate(
                rule("Large payment", RuleScope.PAYMENT, LARGE_AMOUNT, "25.00"),
                batch(customer(), transactions));

        assertThat(result.matchedTransactionIds()).hasSize(8);
        assertThat(result.sampleMatches()).hasSize(RuleEvaluationResult.SAMPLE_LIMIT);
    }

    @Test
    void everySampleCarriesTheTraceThatJustifiesIt() {
        Transaction wire = payment("15000.00", "Completed", T, "SWIFT", "IR");
        RuleEvaluationResult result = evaluator.evaluate(
                rule("Sanctioned wire", RuleScope.PAYMENT, """
                        {"op":"AND","conditions":[
                          {"field":"amount","operator":"GT","value":10000},
                          {"field":"payment.receiver_bank_country","operator":"IN","value":["IR","KP"]}]}
                        """, "30.00"),
                batch(wire));

        RuleMatch match = result.sampleMatches().get(0);
        assertThat(match.transactionId()).isEqualTo(wire.getTransactionId());
        assertThat(match.customerId()).isEqualTo(RuleTestFixtures.CUSTOMER_ID);
        assertThat(match.customerName()).isEqualTo("Dana Kovac");
        assertThat(match.explanation())
                .contains("amount=15000 GT 10000 [true]")
                .contains("payment.receiver_bank_country='IR' IN ['IR', 'KP'] [true]");
        assertThat(result.explanation())
                .contains("triggered on 1 of 1 PAYMENT transaction(s), scoring 30.00 of the rule weight 30.00")
                .contains("Largest match: transaction " + wire.getTransactionId());
    }

    @Test
    void aCustomerWithoutMatchingActivityIsReportedExplicitly() {
        Customer person = customer();
        RuleEvaluationResult result = evaluator.evaluate(
                rule("Crypto exposure", RuleScope.CRYPTO, LARGE_AMOUNT, "20.00"),
                batch(person, card("5000.00", "Completed", T, "SHOP", "5411", "Credit", true, null)));

        assertThat(result.evaluatedTransactionCount()).isZero();
        assertThat(result.triggered()).isFalse();
        assertThat(result.score()).isEqualByComparingTo("0.00");
        assertThat(result.explanation()).contains("no CRYPTO transactions");
    }

    @Test
    void anEmptyBatchIsSafe() {
        RuleEvaluationResult result = evaluator.evaluate(
                rule("Anything", RuleScope.ALL, LARGE_AMOUNT, "20.00"),
                EvaluationBatch.empty(customer()));

        assertThat(result.evaluatedTransactionCount()).isZero();
        assertThat(result.triggered()).isFalse();
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void theBatchExposesTheIdsNeededToRecordFullCoverage() {
        Transaction cardTransaction = card("10.00", "Completed", T, "SHOP", "5411", "Credit", true, null);
        Transaction paymentTransaction = payment("10.00", "Completed", T, "ACH", "US");
        EvaluationBatch fixture = batch(cardTransaction, paymentTransaction);

        assertThat(fixture.transactionIdsFor(RuleScope.ALL))
                .containsExactlyInAnyOrder(cardTransaction.getTransactionId(),
                        paymentTransaction.getTransactionId());
        assertThat(fixture.transactionIdsFor(RuleScope.CARD))
                .containsExactly(cardTransaction.getTransactionId());
        assertThat(fixture.transactionIdsFor(RuleScope.CRYPTO)).isEmpty();
        assertThat(fixture.transactions()).hasSize(2);
    }

    @Test
    void resultsAreImmutable() {
        RuleEvaluationResult result = evaluator.evaluate(
                rule("Large payment", RuleScope.PAYMENT, LARGE_AMOUNT, "25.00"),
                batch(payment("5000.00", "Completed", T, "ACH", "US")));

        List<java.util.UUID> ids = result.matchedTransactionIds();
        assertThat(ids).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ids.add(java.util.UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
