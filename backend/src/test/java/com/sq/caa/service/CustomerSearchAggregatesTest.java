package com.sq.caa.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.AnalysisRun;
import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.PaymentActivity;
import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.Transaction;
import com.sq.caa.web.dto.CustomerDtos.CustomerSummary;
import com.sq.caa.web.dto.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
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
 * The four aggregate columns of the dashboard customer table, and the promise that they stay free.
 *
 * <p>{@code GET /api/customers} is a paged list, so the aggregates must not cost a query per row.
 * Both budget assertions here pin the exact statement count, which is what stops a future
 * "just load it per customer" refactor: a per-row implementation would issue two extra statements
 * for every customer on the page.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CustomerService.class)
class CustomerSearchAggregatesTest {

    /** search + per-page activity rollup + per-page latest verdict. Independent of the row count. */
    private static final long STATEMENTS_PER_SEARCH = 3L;

    @Autowired private TestEntityManager entityManager;
    @Autowired private CustomerService customerService;

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    /** Unique so the search term cannot pick up seeded customers. */
    private final String surname = "Aggtest" + UUID.randomUUID().toString().substring(0, 8);

    private Customer busy;
    private Customer quiet;
    private Customer idle;

    @BeforeEach
    void seedFixture() {
        busy = customer("Alice");
        quiet = customer("Bob");
        idle = customer("Cara");

        // Three USD payments and two CHF ones: USD is dominant on count, CHF on amount.
        transaction(busy, "100.00", "USD", NOW.minus(9, ChronoUnit.DAYS));
        transaction(busy, "200.00", "USD", NOW.minus(8, ChronoUnit.DAYS));
        transaction(busy, "300.00", "USD", NOW.minus(7, ChronoUnit.DAYS));
        transaction(busy, "1000.00", "CHF", NOW.minus(6, ChronoUnit.DAYS));
        transaction(busy, "5000.00", "CHF", NOW.minus(5, ChronoUnit.DAYS));
        transaction(quiet, "42.50", "EUR", NOW.minus(2, ChronoUnit.DAYS));

        // Newest COMPLETED run wins; the later RUNNING run has no verdict and must be ignored.
        analysisRun(busy, AnalysisStatus.COMPLETED, RiskLevel.HIGH, NOW.minus(3, ChronoUnit.HOURS));
        analysisRun(busy, AnalysisStatus.COMPLETED, RiskLevel.LOW, NOW.minus(1, ChronoUnit.HOURS));
        analysisRun(busy, AnalysisStatus.RUNNING, null, NOW);
        analysisRun(quiet, AnalysisStatus.FAILED, null, NOW.minus(1, ChronoUnit.HOURS));

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void searchRowsCarryTheAggregatesTheDashboardTableRenders() {
        List<CustomerSummary> rows = customerService.search(surname, 0, 10).content();
        assertEquals(3, rows.size(), "the three fixture customers, ordered by first name");

        CustomerSummary alice = rows.get(0);
        assertEquals(busy.getCustomerId(), alice.customerId());
        assertEquals(5L, alice.transactionCount());
        assertEquals(new BigDecimal("600.00"), alice.totalAmount(), "dominant currency only");
        assertEquals("USD", alice.totalAmountCurrency(), "most transactions wins, not the largest sum");
        assertTrue(alice.mixedCurrency(), "CHF activity is on file too");
        assertEquals(NOW.minus(5, ChronoUnit.DAYS), alice.lastActivityAt());
        assertEquals(RiskLevel.LOW, alice.lastRiskLevel(), "newest COMPLETED run, not the oldest");
        assertEquals(NOW.minus(1, ChronoUnit.HOURS), alice.lastAnalysisAt());

        CustomerSummary bob = rows.get(1);
        assertEquals(quiet.getCustomerId(), bob.customerId());
        assertEquals(1L, bob.transactionCount());
        assertEquals(new BigDecimal("42.50"), bob.totalAmount());
        assertEquals("EUR", bob.totalAmountCurrency());
        assertFalse(bob.mixedCurrency(), "a single currency on file");
        assertEquals(NOW.minus(2, ChronoUnit.DAYS), bob.lastActivityAt());
        assertNull(bob.lastRiskLevel(), "a FAILED run carries no verdict");
        assertNull(bob.lastAnalysisAt());

        CustomerSummary cara = rows.get(2);
        assertEquals(idle.getCustomerId(), cara.customerId());
        assertEquals(0L, cara.transactionCount());
        assertEquals(new BigDecimal("0.00"), cara.totalAmount());
        assertNull(cara.totalAmountCurrency());
        assertFalse(cara.mixedCurrency());
        assertNull(cara.lastActivityAt());
        assertNull(cara.lastRiskLevel());
    }

    @Test
    void aggregatesCostTheSameWhateverThePageHolds() {
        entityManager.clear();
        long threeRows = statementsOf(() -> customerService.search(surname, 0, 10));
        entityManager.clear();
        long oneRow = statementsOf(() -> customerService.search("Alice " + surname, 0, 10));

        assertEquals(STATEMENTS_PER_SEARCH, threeRows,
                "a page of customers must cost the search plus two page-wide aggregate queries");
        assertEquals(oneRow, threeRows,
                "the statement count must not grow with the number of rows on the page");
    }

    @Test
    void anEmptyPageIssuesNoAggregateQueries() {
        entityManager.clear();
        long statements = statementsOf(() -> customerService.search(surname + "-no-such-customer", 0, 10));
        assertEquals(1L, statements, "nothing to aggregate, so only the search itself runs");
    }

    @Test
    void seededCustomersAreDecoratedToo() {
        PageResponse<CustomerSummary> page = customerService.search(null, 0, 200);
        assertTrue(page.totalElements() >= 15, "the seeded customers plus the fixture");
        assertTrue(page.content().stream().allMatch(row -> row.totalAmount() != null),
                "the total amount is never null, only zero");
        assertTrue(page.content().stream()
                        .filter(row -> !row.lastName().equals(surname))
                        .anyMatch(row -> row.transactionCount() > 0 && row.lastActivityAt() != null
                                && row.totalAmountCurrency() != null),
                "seeded customers carry real activity aggregates");
    }

    private long statementsOf(Runnable call) {
        Statistics statistics = statistics();
        long before = statistics.getPrepareStatementCount();
        call.run();
        return statistics.getPrepareStatementCount() - before;
    }

    private Statistics statistics() {
        return entityManager.getEntityManager().getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
    }

    private Customer customer(String firstName) {
        Customer customer = Customer.builder()
                .customerId(UUID.randomUUID())
                .firstName(firstName)
                .lastName(surname)
                .dob(LocalDate.of(1980, 1, 1))
                .country("CH")
                .build();
        return entityManager.persist(customer);
    }

    private void transaction(Customer owner, String amount, String currency, Instant createdAt) {
        Transaction transaction = Transaction.builder()
                .transactionId(UUID.randomUUID())
                .customer(owner)
                .activityType(ActivityType.PAYMENT)
                .amount(new BigDecimal(amount))
                .currency(currency)
                .status(Transaction.STATUS_COMPLETED)
                .createdAt(createdAt)
                .paymentActivity(PaymentActivity.builder()
                        .paymentMethod("Wire")
                        .senderAccount("CH9300762011623852957")
                        .receiverAccount("DE89370400440532013000")
                        .receiverBankCountry("DE")
                        .build())
                .build();
        entityManager.persist(transaction);
    }

    private void analysisRun(Customer owner, AnalysisStatus status, RiskLevel riskLevel, Instant createdAt) {
        AnalysisRun run = AnalysisRun.builder()
                .assessmentId(UUID.randomUUID())
                .customer(owner)
                .status(status)
                .riskLevel(riskLevel)
                .totalScore(riskLevel == null ? null : new BigDecimal("10.00"))
                .rulesTotal(4)
                .rulesEvaluated(status == AnalysisStatus.COMPLETED ? 4 : 0)
                .coverageComplete(status == AnalysisStatus.COMPLETED)
                .steps(3)
                .trace("{\"steps\":[]}")
                .requestedBy("operator1")
                .createdAt(createdAt)
                .completedAt(status == AnalysisStatus.RUNNING ? null : createdAt.plusSeconds(2))
                .build();
        entityManager.persist(run);
    }
}
