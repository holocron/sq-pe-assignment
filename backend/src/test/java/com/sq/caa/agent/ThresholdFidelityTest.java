package com.sq.caa.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The check on the question rather than on the answer.
 *
 * <p>The first case below is not hypothetical: it is the query a live run wrote for the rule this
 * whole redesign was built around, and the arithmetic in it was exact.
 */
class ThresholdFidelityTest {

    /** The real condition, from the seed. */
    private static final String VELOCITY = """
            Eight or more transactions of any type by the same customer inside a rolling 24-hour \
            window, where the total amount across that same window is also above 40,000. Both parts \
            must hold at the same transaction: agg.tx_count_24h of 8 or more together with \
            agg.amount_sum_24h above 40,000.""";

    @Nested
    @DisplayName("the substituted threshold")
    class Substitution {

        @Test
        void catchesTheQueryThatMissedTheVelocityRuleAgain() {
            String written = """
                    SELECT t.transaction_id FROM tx t
                    WHERE (SELECT count(*) FROM tx w
                           WHERE w.created_at > t.created_at - INTERVAL '24 hours'
                             AND w.created_at <= t.created_at) >= 5
                      AND (SELECT sum(w.amount) FROM tx w
                           WHERE w.created_at > t.created_at - INTERVAL '24 hours'
                             AND w.created_at <= t.created_at) >= 100000
                    """;
            List<String> missing = ThresholdFidelity.missingThresholds(VELOCITY, written);

            assertEquals(List.of("40,000", "8"), missing,
                    "the two numbers the condition states and the query never mentions");
            String reason = ThresholdFidelity.reason("Transaction velocity", missing);
            assertTrue(reason.contains("40,000") && reason.contains("8"), reason);
            assertTrue(reason.contains("Nothing was recorded"), reason);
        }

        @Test
        void passesTheQueryThatAnswersTheConditionAsWritten() {
            String correct = """
                    SELECT t.transaction_id FROM tx t
                    WHERE (SELECT count(*) FROM tx w
                           WHERE w.created_at > t.created_at - INTERVAL '24 hours'
                             AND w.created_at <= t.created_at) >= 8
                      AND (SELECT sum(w.amount) FROM tx w
                           WHERE w.created_at > t.created_at - INTERVAL '24 hours'
                             AND w.created_at <= t.created_at) > 40000
                    """;
            assertEquals(List.of(), ThresholdFidelity.missingThresholds(VELOCITY, correct));
        }

        @Test
        void thousandsSeparatorsAndDecimalsAreTheSameNumber() {
            assertEquals(List.of(), ThresholdFidelity.missingThresholds(
                    "each between 8,000 and 9,999.99, totalling at least 20,000",
                    "WHERE amount BETWEEN 8000 AND 9999.99 AND total >= 20000.00"));
        }

        @Test
        void aNumberInsideAStringLiteralCounts() {
            // MCC codes are text in the schema, so the query writes them quoted. They are still the
            // condition's numbers and must still count as used.
            assertEquals(List.of(), ThresholdFidelity.missingThresholds(
                    "merchant category 7995, 6051 or 4829",
                    "WHERE card.mcc_code IN ('7995', '6051', '4829')"));
        }
    }

    @Nested
    @DisplayName("numbers that are not thresholds")
    class NotAThreshold {

        @Test
        void digitsInsideAWordAreNotANumber() {
            // "P2P transfers are out of scope" - the 2 is a rail, not a bound.
            assertEquals(List.of(), ThresholdFidelity.missingThresholds(
                    "A single payment of 10,000 or more. P2P transfers are out of scope.",
                    "WHERE payment.payment_method IN ('ACH', 'Wire') AND tx.amount >= 10000"));
        }

        @Test
        void digitsInsideAFieldNameAreNotANumber() {
            assertEquals(List.of(), ThresholdFidelity.missingThresholds(
                    "agg.failed_count_24h counts them",
                    "SELECT tx.transaction_id FROM tx"));
        }

        @Test
        void aConditionWithNoNumbersAsksNothing() {
            assertEquals(List.of(), ThresholdFidelity.missingThresholds(
                    "Any crypto transfer with no attributed exchange.",
                    "SELECT crypto.transaction_id FROM crypto WHERE crypto.exchange_name IS NULL"));
        }

        @Test
        void nullAndBlankInputsAreNotAFailure() {
            assertEquals(List.of(), ThresholdFidelity.missingThresholds(null, "SELECT 1"));
            assertEquals(List.of(), ThresholdFidelity.missingThresholds("above 40,000", null));
            assertEquals(List.of(), ThresholdFidelity.missingThresholds("  ", "  "));
        }
    }

    @Nested
    @DisplayName("the false positive this check is allowed to have")
    class KnownFalsePositive {

        private static final String HOURS = "above 15,000 booked between 00:00 and 05:59 UTC";

        /**
         * The reason the check is advisory. "Booked between 00:00 and 05:59" is answered correctly
         * by an hour comparison that never writes 59, so this flags a query that is right. It costs
         * the model one turn and a sentence telling it that resending the query unchanged is a
         * legitimate answer - see {@link ThresholdFidelity#hint()} - and it must never be able to
         * leave a rule unjudged, which is why {@code RiskAgentTools} never runs it on a rule's last
         * attempt.
         */
        @Test
        void anHourRangeWrittenAsAComparisonIsFlaggedAndTheHintSaysWhatToDo() {
            // The form a live run actually wrote. It names the hours, so only the minute is missing.
            assertEquals(List.of("59"), ThresholdFidelity.missingThresholds(HOURS,
                    "SELECT tx.transaction_id FROM tx WHERE tx.amount > 15000 "
                            + "AND extract(hour FROM tx.created_at) BETWEEN 0 AND 5"));

            // The equally correct form that names none of them. The check is this crude on purpose;
            // what stops it costing a verdict is that it runs once and takes "unchanged" for an
            // answer.
            assertEquals(List.of("05", "59"), ThresholdFidelity.missingThresholds(HOURS,
                    "SELECT tx.transaction_id FROM tx WHERE tx.amount > 15000 "
                            + "AND extract(hour FROM tx.created_at) < 6"));

            assertTrue(ThresholdFidelity.hint().contains("unchanged"),
                    "the model has to be told that resending the same query is a valid answer");
        }
    }

    @Nested
    @DisplayName("the message")
    class Message {

        @Test
        void namesAtMostSixNumbersAndCountsTheRest() {
            String condition = "codes 1, 2, 3, 4, 5, 6, 7, 8 and 9";  // zero is never required
            List<String> missing = ThresholdFidelity.missingThresholds(
                    condition, "SELECT tx.transaction_id FROM tx");

            assertEquals(9, missing.size());
            String reason = ThresholdFidelity.reason("Nine codes", missing);
            assertTrue(reason.contains("and 3 more"), reason);
            assertTrue(reason.length() < 400, "the caller caps the reason at 400 characters: " + reason);
        }

        @Test
        void aRepeatedNumberIsNamedOnce() {
            List<String> missing = ThresholdFidelity.missingThresholds(
                    "above 40,000 ... together with amounts above 40,000",
                    "SELECT tx.transaction_id FROM tx");
            assertEquals(List.of("40,000"), missing);
        }
    }
}
