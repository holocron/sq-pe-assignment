package com.sq.caa.rules;

import static com.sq.caa.rules.RuleTestFixtures.crypto;
import static com.sq.caa.rules.RuleTestFixtures.list;
import static com.sq.caa.rules.RuleTestFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The {@code agg.*} window sweep.
 *
 * <p>These aggregates are the part of the DSL that is easy to get quietly wrong, so the windows are
 * pinned here: they end at the transaction, include it, and exclude the far edge.
 */
class AggregateCalculatorTest {

    private static final Instant T = Instant.parse("2026-08-20T12:00:00Z");

    private static Instant before(Duration duration) {
        return T.minus(duration);
    }

    @Test
    void windowsEndAtTheTransactionAndIncludeIt() {
        Transaction old = payment("100.00", "Completed", before(Duration.ofHours(40)), "ACH", "US");
        Transaction recent = payment("200.00", "Completed", before(Duration.ofHours(23)), "ACH", "DE");
        Transaction failed = payment("300.00", "Failed", before(Duration.ofHours(2)), "SWIFT", "FR");
        Transaction current = crypto("400.00", "Completed", T, "BTC", "Kraken", "wallet-x");

        Map<UUID, AggregateSnapshot> snapshots =
                AggregateCalculator.compute(list(old, recent, failed, current));

        AggregateSnapshot latest = snapshots.get(current.getTransactionId());
        assertThat(latest.txCount24h()).isEqualTo(3);
        assertThat(latest.amountSum24h()).isEqualByComparingTo("900.00");
        assertThat(latest.failedCount24h()).isEqualTo(1);
        assertThat(latest.distinctCountries30d()).isEqualTo(3);
        assertThat(latest.maxAmount30d()).isEqualByComparingTo("400.00");
        assertThat(latest.cryptoRatio30d()).isEqualByComparingTo("0.2500");

        AggregateSnapshot middle = snapshots.get(failed.getTransactionId());
        assertThat(middle.txCount24h()).isEqualTo(2);
        assertThat(middle.amountSum24h()).isEqualByComparingTo("500.00");
        assertThat(middle.failedCount24h()).isEqualTo(1);
        assertThat(middle.maxAmount30d()).isEqualByComparingTo("300.00");
        assertThat(middle.cryptoRatio30d()).isEqualByComparingTo("0.0000");

        AggregateSnapshot oldest = snapshots.get(old.getTransactionId());
        assertThat(oldest.txCount24h()).isEqualTo(1);
        assertThat(oldest.amountSum24h()).isEqualByComparingTo("100.00");
        assertThat(oldest.distinctCountries30d()).isEqualTo(1);
    }

    @Test
    void theFarEdgeOfTheWindowIsExcluded() {
        Transaction exactlyOnEdge = payment("50.00", "Completed", before(Duration.ofHours(24)), "ACH", "US");
        Transaction justInside = payment("60.00", "Completed",
                before(Duration.ofHours(24)).plusSeconds(1), "ACH", "US");
        Transaction current = payment("70.00", "Completed", T, "ACH", "US");

        AggregateSnapshot snapshot = AggregateCalculator
                .compute(list(exactlyOnEdge, justInside, current))
                .get(current.getTransactionId());

        assertThat(snapshot.txCount24h()).isEqualTo(2);
        assertThat(snapshot.amountSum24h()).isEqualByComparingTo("130.00");
    }

    @Test
    void thirtyDayWindowDropsOlderActivity() {
        Transaction ancient = payment("900.00", "Completed", before(Duration.ofDays(31)), "ACH", "GB");
        Transaction current = payment("70.00", "Completed", T, "ACH", "US");

        AggregateSnapshot snapshot = AggregateCalculator.compute(list(ancient, current))
                .get(current.getTransactionId());

        assertThat(snapshot.maxAmount30d()).isEqualByComparingTo("70.00");
        assertThat(snapshot.distinctCountries30d()).isEqualTo(1);
    }

    @Test
    void distinctCountriesCountsPaymentBeneficiariesOnly() {
        Transaction one = payment("10.00", "Completed", before(Duration.ofDays(5)), "SWIFT", "IR");
        Transaction two = payment("10.00", "Completed", before(Duration.ofDays(4)), "SWIFT", "ir");
        Transaction three = payment("10.00", "Completed", before(Duration.ofDays(3)), "SWIFT", "KP");
        Transaction cryptoTransfer = crypto("10.00", "Completed", T, "XMR", null, "wallet-x");

        AggregateSnapshot snapshot = AggregateCalculator.compute(list(one, two, three, cryptoTransfer))
                .get(cryptoTransfer.getTransactionId());

        assertThat(snapshot.distinctCountries30d()).isEqualTo(2);
    }

    @Test
    void cryptoRatioIsAShareOfActivityBetweenZeroAndOne() {
        Transaction c1 = crypto("10.00", "Completed", before(Duration.ofDays(3)), "XMR", null, "w1");
        Transaction c2 = crypto("10.00", "Completed", before(Duration.ofDays(2)), "BTC", "Kraken", "w2");
        Transaction c3 = crypto("10.00", "Completed", T, "ETH", "Kraken", "w3");

        AggregateSnapshot snapshot = AggregateCalculator.compute(list(c1, c2, c3))
                .get(c3.getTransactionId());

        assertThat(snapshot.cryptoRatio30d()).isEqualByComparingTo("1.0000");
    }

    @Test
    void maximumAmountIsRecomputedWhenTheLargestTransactionLeavesTheWindow() {
        Transaction big = payment("5000.00", "Completed", before(Duration.ofDays(31)), "ACH", "US");
        Transaction mid = payment("900.00", "Completed", before(Duration.ofDays(10)), "ACH", "US");
        Transaction current = payment("100.00", "Completed", T, "ACH", "US");

        Map<UUID, AggregateSnapshot> snapshots = AggregateCalculator.compute(list(big, mid, current));

        assertThat(snapshots.get(big.getTransactionId()).maxAmount30d()).isEqualByComparingTo("5000.00");
        assertThat(snapshots.get(current.getTransactionId()).maxAmount30d()).isEqualByComparingTo("900.00");
    }

    @Test
    void anEmptyHistoryProducesNoSnapshots() {
        assertThat(AggregateCalculator.compute(List.of())).isEmpty();
    }

    @Test
    void batchExposesTheSnapshotItUsed() {
        Transaction one = payment("100.00", "Completed", before(Duration.ofHours(3)), "ACH", "US");
        Transaction two = payment("100.00", "Completed", T, "ACH", "US");
        EvaluationBatch batch = RuleTestFixtures.batch(one, two);

        assertThat(batch.aggregatesFor(two.getTransactionId()).txCount24h()).isEqualTo(2);
        assertThat(batch.aggregatesFor(UUID.randomUUID())).isEqualTo(AggregateSnapshot.EMPTY);
    }
}
