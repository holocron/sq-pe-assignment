package com.sq.caa.rules;

import static com.sq.caa.rules.RuleTestFixtures.card;
import static com.sq.caa.rules.RuleTestFixtures.crypto;
import static com.sq.caa.rules.RuleTestFixtures.customer;
import static com.sq.caa.rules.RuleTestFixtures.evaluate;
import static com.sq.caa.rules.RuleTestFixtures.factsOf;
import static com.sq.caa.rules.RuleTestFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.Customer;
import com.sq.caa.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Every operator of the DSL, on every value type it is defined for. */
class RuleEvaluatorOperatorTest {

    private static final Instant AT = Instant.parse("2026-08-20T09:30:00Z");

    private final RuleEvaluator evaluator = new RuleEvaluator();

    private static Transaction cardTx(String amount) {
        return card(amount, "Completed", AT, "CASINO ROYALE LTD", "7995", "Credit", true, null);
    }

    @Nested
    @DisplayName("numeric comparison")
    class Numeric {

        @Test
        void gtIsStrict() {
            Transaction transaction = cardTx("10000.00");
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.GT, new BigDecimal("9999.99")),
                    transaction).matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.GT, new BigDecimal("10000")),
                    transaction).matched()).isFalse();
        }

        @Test
        void gteIncludesTheBoundary() {
            Transaction transaction = cardTx("10000.00");
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.GTE, new BigDecimal("10000")),
                    transaction).matched()).isTrue();
        }

        @Test
        void ltAndLte() {
            Transaction transaction = cardTx("500.00");
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.LT, new BigDecimal("501")),
                    transaction).matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.LT, new BigDecimal("500")),
                    transaction).matched()).isFalse();
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.LTE, new BigDecimal("500")),
                    transaction).matched()).isTrue();
        }

        @Test
        void equalityIgnoresScale() {
            Transaction transaction = cardTx("10000.00");
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.EQ, new BigDecimal("10000")),
                    transaction).matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.NEQ, new BigDecimal("10000.0000")),
                    transaction).matched()).isFalse();
        }

        @Test
        void betweenIsInclusiveOnBothEnds() {
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.BETWEEN,
                    List.of(new BigDecimal("8000"), new BigDecimal("9999.99"))), cardTx("8000.00"))
                    .matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.BETWEEN,
                    List.of(new BigDecimal("8000"), new BigDecimal("9999.99"))), cardTx("9999.99"))
                    .matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.BETWEEN,
                    List.of(new BigDecimal("8000"), new BigDecimal("9999.99"))), cardTx("10000.00"))
                    .matched()).isFalse();
        }

        @Test
        void inAndNotInCompareNumerically() {
            Transaction transaction = cardTx("500.00");
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.IN,
                    List.of(new BigDecimal("100"), new BigDecimal("500.000"))), transaction).matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.NOT_IN,
                    List.of(new BigDecimal("100"), new BigDecimal("500.000"))), transaction).matched()).isFalse();
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.NOT_IN,
                    List.of(new BigDecimal("100"))), transaction).matched()).isTrue();
        }

        @Test
        void numericStringOperandsAreAccepted() {
            assertThat(evaluate(RuleCondition.of("amount", RuleOperator.GT, "100"), cardTx("500.00"))
                    .matched()).isTrue();
        }

        @Test
        void derivedHourOfDayIsUtc() {
            Transaction transaction = cardTx("10.00");
            assertThat(evaluate(RuleCondition.of("hour_of_day", RuleOperator.EQ, new BigDecimal("9")),
                    transaction).matched()).isTrue();
        }

        @Test
        void derivedCustomerAgeComesFromDateOfBirth() {
            Customer young = customer("DE", 22);
            RuleEvaluator local = new RuleEvaluator();
            NodeOutcome outcome = local.evaluateNode(
                    RuleCondition.of("customer.age", RuleOperator.LT, new BigDecimal("25")),
                    factsOf(young, cardTx("10.00")));
            assertThat(outcome.matched()).isTrue();
        }
    }

    @Nested
    @DisplayName("text comparison")
    class Text {

        @Test
        void equalityIsCaseInsensitive() {
            Transaction transaction = cardTx("10.00");
            assertThat(evaluate(RuleCondition.of("status", RuleOperator.EQ, "completed"), transaction)
                    .matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("status", RuleOperator.NEQ, "COMPLETED"), transaction)
                    .matched()).isFalse();
        }

        @Test
        void inAndNotInOnCountryCodes() {
            Transaction transaction = payment("100.00", "Completed", AT, "SWIFT", "IR");
            assertThat(evaluate(RuleCondition.of("payment.receiver_bank_country", RuleOperator.IN,
                    List.of("IR", "KP", "SY")), transaction).matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("payment.receiver_bank_country", RuleOperator.NOT_IN,
                    List.of("IR", "KP", "SY")), transaction).matched()).isFalse();
            assertThat(evaluate(RuleCondition.of("payment.receiver_bank_country", RuleOperator.NOT_IN,
                    List.of("US", "GB")), transaction).matched()).isTrue();
        }

        @Test
        void containsIsCaseInsensitiveSubstring() {
            Transaction transaction = cardTx("10.00");
            assertThat(evaluate(RuleCondition.of("card.merchant_name", RuleOperator.CONTAINS, "casino"),
                    transaction).matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("card.merchant_name", RuleOperator.NOT_CONTAINS, "casino"),
                    transaction).matched()).isFalse();
            assertThat(evaluate(RuleCondition.of("card.merchant_name", RuleOperator.NOT_CONTAINS, "grocery"),
                    transaction).matched()).isTrue();
        }

        @Test
        void containsAcceptsAListAndMatchesAnyOfIt() {
            Transaction transaction = cardTx("10.00");
            assertThat(evaluate(RuleCondition.of("card.merchant_name", RuleOperator.CONTAINS,
                    List.of("bookmaker", "royale")), transaction).matched()).isTrue();
        }

        @Test
        void matchesSearchesAndIgnoresCase() {
            Transaction transaction = cardTx("10.00");
            assertThat(evaluate(RuleCondition.of("card.merchant_name", RuleOperator.MATCHES,
                    "(casino|betting|forex)"), transaction).matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("card.merchant_name", RuleOperator.MATCHES,
                    "^ROYALE"), transaction).matched()).isFalse();
        }

        @Test
        void matchesWithAnInvalidRegexIsFalseAndDegraded() {
            NodeOutcome outcome = evaluate(
                    RuleCondition.of("card.merchant_name", RuleOperator.MATCHES, "([unclosed"), cardTx("10.00"));
            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.degraded()).isTrue();
            assertThat(outcome.notes()).anyMatch(note -> note.contains("not a valid regular expression"));
        }

        @Test
        void mccCodesCompareAsText() {
            Transaction transaction = cardTx("10.00");
            assertThat(evaluate(RuleCondition.of("card.mcc_code", RuleOperator.IN,
                    List.of("7995", "7801")), transaction).matched()).isTrue();
        }
    }

    @Nested
    @DisplayName("boolean, timestamp and null checks")
    class Others {

        @Test
        void booleanEqualityWorksBothWays() {
            Transaction present = card("10.00", "Completed", AT, "SHOP", "5411", "Debit", true, null);
            Transaction notPresent = card("10.00", "Completed", AT, "SHOP", "5411", "Debit", false, null);
            assertThat(evaluate(RuleCondition.of("card.card_present", RuleOperator.EQ, Boolean.FALSE),
                    notPresent).matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("card.card_present", RuleOperator.EQ, Boolean.FALSE),
                    present).matched()).isFalse();
            assertThat(evaluate(RuleCondition.of("card.card_present", RuleOperator.NEQ, Boolean.TRUE),
                    notPresent).matched()).isTrue();
        }

        @Test
        void timestampsCompareAgainstIsoStrings() {
            Transaction transaction = cardTx("10.00");
            assertThat(evaluate(RuleCondition.of("created_at", RuleOperator.GTE, "2026-08-20T00:00:00Z"),
                    transaction).matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("created_at", RuleOperator.LT, "2026-08-20"),
                    transaction).matched()).isFalse();
            assertThat(evaluate(RuleCondition.of("created_at", RuleOperator.BETWEEN,
                    List.of("2026-08-19", "2026-08-21")), transaction).matched()).isTrue();
        }

        @Test
        void isNullAndNotNullOnANullableColumn() {
            Transaction declined = card("10.00", "Failed", AT, "SHOP", "5411", "Debit", true,
                    "Insufficient funds");
            Transaction approved = card("10.00", "Completed", AT, "SHOP", "5411", "Debit", true, null);
            assertThat(evaluate(RuleCondition.of("card.decline_reason", RuleOperator.NOT_NULL), declined)
                    .matched()).isTrue();
            assertThat(evaluate(RuleCondition.of("card.decline_reason", RuleOperator.IS_NULL), declined)
                    .matched()).isFalse();
            assertThat(evaluate(RuleCondition.of("card.decline_reason", RuleOperator.IS_NULL), approved)
                    .matched()).isTrue();
        }

        @Test
        void blankTextCountsAsAbsent() {
            Transaction unattributed = crypto("10.00", "Completed", AT, "XMR", "   ", "wallet-x");
            NodeOutcome outcome = evaluate(
                    RuleCondition.of("crypto.exchange_name", RuleOperator.IS_NULL), unattributed);
            assertThat(outcome.matched()).isTrue();
            assertThat(outcome.degraded()).isFalse();
        }

        @Test
        void nullChecksNeverDegrade() {
            Transaction approved = card("10.00", "Completed", AT, "SHOP", "5411", "Debit", true, null);
            assertThat(evaluate(RuleCondition.of("card.decline_reason", RuleOperator.IS_NULL), approved)
                    .degraded()).isFalse();
            assertThat(evaluate(RuleCondition.of("card.decline_reason", RuleOperator.NOT_NULL), approved)
                    .degraded()).isFalse();
        }

        @Test
        void activityTypeIsComparableAsAnEnumName() {
            assertThat(evaluate(RuleCondition.of("activity_type", RuleOperator.EQ, "CRYPTO"),
                    crypto("10.00", "Completed", AT, "BTC", "Kraken", "wallet-x")).matched()).isTrue();
        }

        @Test
        void everyOperatorIsExercisedBySomeTest() {
            // Guards against an operator being added to the enum without a test.
            assertThat(RuleOperator.values()).hasSize(14);
        }
    }

    @Test
    void traceExplainsBothTheValueAndTheOutcome() {
        NodeOutcome outcome = evaluator.evaluateNode(
                RuleCondition.of("amount", RuleOperator.GT, new BigDecimal("100")),
                RuleTestFixtures.facts(cardTx("500.00")));
        assertThat(outcome.explanation()).isEqualTo("amount=500 GT 100 [true]");
    }
}
