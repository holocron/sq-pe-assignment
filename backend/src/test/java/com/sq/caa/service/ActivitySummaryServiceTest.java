package com.sq.caa.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.CardActivity;
import com.sq.caa.domain.CryptoActivity;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.PaymentActivity;
import com.sq.caa.domain.Transaction;
import com.sq.caa.rules.AggregateSnapshot;
import com.sq.caa.rules.EvaluationBatch;
import com.sq.caa.web.dto.CustomerDtos.CustomerActivitySummary;
import com.sq.caa.web.dto.CustomerDtos.DailyAmount;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

/**
 * The customer summary payload: its velocity block and its query budget.
 *
 * <p>Two things are pinned here. First, the six {@code agg.*} figures the operator reads must be the
 * ones the agent reads through its tools - a divergence would have the UI contradict the verdict. Second,
 * the endpoint must issue a fixed number of statements: it used to load the timeline as entities,
 * which cost three extra selects per transaction because the nullable inverse to-one detail rows
 * cannot be proxied.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CustomerService.class, RiskRuleService.class, ActivitySummaryService.class})
class ActivitySummaryServiceTest {

    /**
     * customer + four grouped rollups + analysis history + the one fetch-joined activity read.
     * Constant: nothing here may scale with the number of transactions.
     */
    private static final long STATEMENTS_PER_SUMMARY = 7L;

    @Autowired private TestEntityManager entityManager;
    @Autowired private ActivitySummaryService summaries;
    @Autowired private RiskRuleService riskRules;

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private Customer customer;
    private Customer busy;
    private UUID newestTransactionId;

    @BeforeEach
    void seedFixture() {
        customer = customer("Vela");

        // Old enough to fall out of the recent 30 day windows, recent enough to be in the crypto one.
        payment(customer, "1000.00", "DE", Transaction.STATUS_COMPLETED, NOW.minus(35, ChronoUnit.DAYS));
        // Inside 30d, outside 24h.
        crypto(customer, "5000.00", NOW.minus(10, ChronoUnit.DAYS));
        // Inside 24h.
        payment(customer, "2000.00", "IR", Transaction.STATUS_FAILED, NOW.minus(3, ChronoUnit.HOURS));
        card(customer, "300.00", NOW.minus(1, ChronoUnit.HOURS));
        newestTransactionId =
                payment(customer, "700.00", "DE", Transaction.STATUS_COMPLETED, NOW.minus(10, ChronoUnit.MINUTES));

        busy = customer("Volume");
        for (int i = 0; i < 20; i++) {
            card(busy, "10.00", NOW.minus(i + 1L, ChronoUnit.HOURS));
        }

        entityManager.flush();
        entityManager.clear();
        riskRules.invalidateAllBatches();
    }

    @Test
    void velocityIsThePeakOfTheRuleEnginesOwnWindows() {
        CustomerActivitySummary summary = summaries.summarise(customer.getCustomerId());

        assertEquals(3L, summary.txCount24h(), "the failed payment, the card and the newest payment");
        assertEquals(new BigDecimal("5000.00"), summary.amountSum24h(),
                "the crypto transaction alone is the busiest 24 hours by amount");
        assertEquals(1L, summary.failedCount24h());
        assertEquals(2L, summary.distinctCountries30d(), "IR and DE seen in one 30 day window");
        assertEquals(0, new BigDecimal("0.5000").compareTo(summary.cryptoRatio30d()),
                "one crypto of the two transactions in the window ending at the crypto one");
        assertEquals(new BigDecimal("5000.00"), summary.maxAmount30d());

        EvaluationBatch batch = riskRules.batchFor(customer.getCustomerId());

        // The peak is not the newest snapshot: reporting that one would understate the customer.
        AggregateSnapshot atNewest = batch.aggregatesFor(newestTransactionId);
        assertEquals(new BigDecimal("3000.00"), atNewest.amountSum24h());
        assertTrue(summary.amountSum24h().compareTo(atNewest.amountSum24h()) > 0);

        // The guarantee that matters: every figure is the peak of the engine's own snapshots, so a
        // rule can only trigger on a number the operator can also see.
        assertEquals(peak(batch, AggregateSnapshot::txCount24h), summary.txCount24h());
        assertEquals(peak(batch, AggregateSnapshot::failedCount24h), summary.failedCount24h());
        assertEquals(peak(batch, AggregateSnapshot::distinctCountries30d), summary.distinctCountries30d());
        assertEquals(peak(batch, AggregateSnapshot::amountSum24h), summary.amountSum24h());
        assertEquals(peak(batch, AggregateSnapshot::cryptoRatio30d), summary.cryptoRatio30d());
        assertEquals(peak(batch, AggregateSnapshot::maxAmount30d), summary.maxAmount30d());
    }

    private long peak(EvaluationBatch batch, ToLongFunction<AggregateSnapshot> field) {
        return batch.transactions().stream()
                .mapToLong(t -> field.applyAsLong(batch.aggregatesFor(t.getTransactionId())))
                .max().orElse(0L);
    }

    private BigDecimal peak(EvaluationBatch batch, Function<AggregateSnapshot, BigDecimal> field) {
        return batch.transactions().stream()
                .map(t -> field.apply(batch.aggregatesFor(t.getTransactionId())))
                .reduce(BigDecimal.ZERO, BigDecimal::max);
    }

    @Test
    void aCustomerWithoutActivityReportsZeroVelocity() {
        Customer empty = customer("Void");
        entityManager.flush();
        entityManager.clear();

        CustomerActivitySummary summary = summaries.summarise(empty.getCustomerId());
        assertEquals(0L, summary.txCount24h());
        assertEquals(BigDecimal.ZERO, summary.amountSum24h());
        assertEquals(0L, summary.failedCount24h());
        assertEquals(0L, summary.distinctCountries30d());
        assertEquals(BigDecimal.ZERO, summary.cryptoRatio30d());
        assertEquals(BigDecimal.ZERO, summary.maxAmount30d());
        assertNull(summary.customer().lastActivityAt());
        assertEquals(0L, summary.customer().transactionCount());
    }

    @Test
    void theNestedCustomerRowCarriesTheSameAggregatesAsTheList() {
        CustomerActivitySummary summary = summaries.summarise(customer.getCustomerId());

        assertEquals(5L, summary.customer().transactionCount());
        assertEquals(summary.totalTransactions(), summary.customer().transactionCount());
        assertEquals(new BigDecimal("9000.00"), summary.customer().totalAmount(), "all five are USD");
        assertEquals("USD", summary.customer().totalAmountCurrency());
        assertFalse(summary.customer().mixedCurrency());
        assertEquals(NOW.minus(10, ChronoUnit.MINUTES), summary.customer().lastActivityAt());
        assertNull(summary.customer().lastRiskLevel(), "never analysed");
    }

    @Test
    void theTimelineCoversThirtyDaysAndExcludesOlderActivity() {
        CustomerActivitySummary summary = summaries.summarise(customer.getCustomerId());

        assertEquals(30, summary.dailyTimeline().size());
        long counted = summary.dailyTimeline().stream()
                .mapToLong(DailyAmount::transactionCount).sum();
        assertEquals(4L, counted, "the 35-day-old payment is outside the window");

        LocalDate cryptoDay = NOW.minus(10, ChronoUnit.DAYS).atZone(ZoneOffset.UTC).toLocalDate();
        DailyAmount bucket = summary.dailyTimeline().stream()
                .filter(day -> day.date().equals(cryptoDay)).findFirst().orElseThrow();
        assertEquals(1L, bucket.transactionCount());
        assertEquals(new BigDecimal("5000.00"), bucket.totalAmount());
        assertTrue(summary.dailyTimeline().getFirst().date()
                .isBefore(summary.dailyTimeline().getLast().date()), "oldest first");
    }

    @Test
    void theSummaryCostsTheSameForFiveTransactionsAndForTwenty() {
        long small = statementsOf(() -> summaries.summarise(customer.getCustomerId()));
        long large = statementsOf(() -> summaries.summarise(busy.getCustomerId()));

        assertEquals(STATEMENTS_PER_SUMMARY, small,
                "the summary must not read the timeline row by row");
        assertEquals(small, large,
                "the statement count must not grow with the customer's activity");
    }

    private long statementsOf(Supplier<CustomerActivitySummary> call) {
        riskRules.invalidateAllBatches();
        entityManager.clear();
        Statistics statistics = statistics();
        long before = statistics.getPrepareStatementCount();
        assertNotNull(call.get());
        return statistics.getPrepareStatementCount() - before;
    }

    private Statistics statistics() {
        return entityManager.getEntityManager().getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
    }

    private Customer customer(String firstName) {
        return entityManager.persist(Customer.builder()
                .customerId(UUID.randomUUID())
                .firstName(firstName)
                .lastName("Velotest" + UUID.randomUUID().toString().substring(0, 8))
                .dob(LocalDate.of(1981, 5, 4))
                .country("CH")
                .build());
    }

    private UUID payment(Customer owner, String amount, String receiverCountry, String status,
            Instant createdAt) {
        return persist(Transaction.builder()
                .transactionId(UUID.randomUUID())
                .customer(owner)
                .activityType(ActivityType.PAYMENT)
                .amount(new BigDecimal(amount))
                .currency("USD")
                .status(status)
                .createdAt(createdAt)
                .paymentActivity(PaymentActivity.builder()
                        .paymentMethod("SWIFT")
                        .senderAccount("CH9300762011623852957")
                        .receiverAccount("DE89370400440532013000")
                        .receiverBankCountry(receiverCountry)
                        .build())
                .build());
    }

    private UUID card(Customer owner, String amount, Instant createdAt) {
        return persist(Transaction.builder()
                .transactionId(UUID.randomUUID())
                .customer(owner)
                .activityType(ActivityType.CARD)
                .amount(new BigDecimal(amount))
                .currency("USD")
                .status(Transaction.STATUS_COMPLETED)
                .createdAt(createdAt)
                .cardActivity(CardActivity.builder()
                        .cardPan("****1234")
                        .cardType("Credit")
                        .merchantName("Initech")
                        .mccCode("5411")
                        .cardPresent(true)
                        .authorizationCode("B42Z01")
                        .build())
                .build());
    }

    private UUID crypto(Customer owner, String amount, Instant createdAt) {
        return persist(Transaction.builder()
                .transactionId(UUID.randomUUID())
                .customer(owner)
                .activityType(ActivityType.CRYPTO)
                .amount(new BigDecimal(amount))
                .currency("USD")
                .status(Transaction.STATUS_COMPLETED)
                .createdAt(createdAt)
                .cryptoActivity(CryptoActivity.builder()
                        .blockchain("BTC")
                        .walletAddressFrom("bc1qfrom")
                        .walletAddressTo("bc1qto")
                        .txHash("0xabc")
                        .exchangeName("Kraken")
                        .build())
                .build());
    }

    private UUID persist(Transaction transaction) {
        return entityManager.persist(transaction).getTransactionId();
    }
}
