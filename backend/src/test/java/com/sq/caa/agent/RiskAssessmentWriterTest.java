package com.sq.caa.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.AnalysisRun;
import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskAssessment;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.domain.Transaction;
import com.sq.caa.repository.RiskAssessmentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How the {@code risk_assessments} rows of a run reach the database.
 *
 * <p>Two things are asserted against the real PostgreSQL instance. First the guarantee itself: a run
 * leaves exactly one row per (transaction, rule) pair evaluated, including the rules that did not
 * trigger - the property BUILD_SPEC section 7 says makes "no rule was skipped" provable from the
 * table alone, and which until now was only ever checked on hand-built lists in memory.
 *
 * <p>Second the cost of writing them. {@code JpaRepository.saveAll} routes every row through
 * {@code em.merge}, because Spring Data's "is this new?" test is "is the id null?" and these rows
 * carry an assigned composite id. Hibernate then reads each row by primary key before inserting it -
 * a read that cannot ever find anything, since the run's previous rows were removed by a bulk delete
 * moments earlier - and sends the inserts one at a time. {@link RiskAssessmentWriter} persists and
 * batches instead. The test measures both and fails if the read-before-write comes back.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.sq.caa.agent.CountingStatementInspector"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RiskAssessmentWriterTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    /** Close to the real thing: the seeded database's one completed run wrote 220 rows. */
    private static final int TRANSACTIONS = 24;
    private static final int RULES = 10;

    @Autowired private TestEntityManager entityManager;
    @Autowired private RiskAssessmentRepository riskAssessments;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    private Customer customer;
    private List<UUID> transactionIds;
    private List<RiskRule> rules;

    @BeforeEach
    void seedOneCustomersWorthOfEvidence() {
        customer = entityManager.persistFlushFind(Customer.builder()
                .customerId(UUID.randomUUID())
                .firstName("Dana")
                .lastName("Kovac")
                .dob(LocalDate.of(1984, 3, 11))
                .country("CH")
                .build());

        transactionIds = new ArrayList<>(TRANSACTIONS);
        for (int index = 0; index < TRANSACTIONS; index++) {
            Transaction transaction = entityManager.persistFlushFind(Transaction.builder()
                    .transactionId(UUID.randomUUID())
                    .customer(customer)
                    .activityType(ActivityType.PAYMENT)
                    .amount(new BigDecimal("9500.00"))
                    .currency("CHF")
                    .status(Transaction.STATUS_COMPLETED)
                    .createdAt(NOW.minus(index + 1, ChronoUnit.HOURS))
                    .build());
            transactionIds.add(transaction.getTransactionId());
        }

        rules = new ArrayList<>(RULES);
        for (int index = 0; index < RULES; index++) {
            rules.add(entityManager.persistFlushFind(RiskRule.builder()
                    .ruleId(UUID.randomUUID())
                    .ruleName("Writer rule " + index + " " + UUID.randomUUID())
                    .appliesTo(RuleScope.ALL)
                    .thresholdLogic("{\"field\":\"amount\",\"operator\":\"GT\",\"value\":1000}")
                    .weight(new BigDecimal("10.00"))
                    .build()));
        }
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("a run's rows are inserted without a read-before-write, and in batches")
    void theWriterInsertsWithoutReadingFirst() {
        UUID viaRepository = UUID.randomUUID();
        UUID viaWriter = UUID.randomUUID();
        List<RiskAssessment> repositoryRows = rows(viaRepository);
        List<RiskAssessment> writerRows = rows(viaWriter);
        assertEquals(TRANSACTIONS * RULES, repositoryRows.size());

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        // --- how it used to be written ------------------------------------
        statistics.clear();
        CountingStatementInspector.reset();
        riskAssessments.saveAll(repositoryRows);
        entityManager.flush();
        long mergeReads = CountingStatementInspector.selects();
        long mergeStatements = CountingStatementInspector.statements();
        long mergeInserts = statistics.getEntityInsertCount();
        entityManager.clear();

        // --- how it is written now ----------------------------------------
        statistics.clear();
        CountingStatementInspector.reset();
        int written = RiskAssessmentWriter.write(entityManager.getEntityManager(), writerRows);
        long writerReads = CountingStatementInspector.selects();
        long writerStatements = CountingStatementInspector.statements();
        long writerInserts = statistics.getEntityInsertCount();

        System.out.printf("%nrisk_assessments write of %d rows:%n"
                        + "  repository.saveAll  : %d SQL statements (%d of them reads), %d rows inserted%n"
                        + "  RiskAssessmentWriter: %d SQL statements (%d of them reads), %d rows inserted%n%n",
                repositoryRows.size(), mergeStatements, mergeReads, mergeInserts,
                writerStatements, writerReads, writerInserts);

        assertEquals(TRANSACTIONS * RULES, written);
        assertEquals(mergeInserts, writerInserts, "the same rows are written either way");

        assertEquals(repositoryRows.size(), mergeReads,
                "saveAll reads every row back before inserting it - that is the defect");
        assertEquals(0, writerReads,
                "persisting states what the caller already knows: these rows are new");

        int expectedBatches = (repositoryRows.size() + RiskAssessmentWriter.BATCH_SIZE - 1)
                / RiskAssessmentWriter.BATCH_SIZE;
        assertEquals(expectedBatches, writerStatements,
                "one batched insert statement per " + RiskAssessmentWriter.BATCH_SIZE + " rows");
        assertTrue(mergeStatements > 10L * writerStatements,
                "the old path cost " + mergeStatements + " statements, the new one " + writerStatements);
    }

    @Test
    @DisplayName("one row per (transaction, rule) pair evaluated survives the round trip to the database")
    void everyEvaluatedPairIsPersisted() {
        UUID assessmentId = UUID.randomUUID();
        RiskAssessmentWriter.write(entityManager.getEntityManager(), rows(assessmentId));
        entityManager.flush();
        entityManager.clear();

        assertEquals((long) TRANSACTIONS * RULES, riskAssessments.countById_AssessmentId(assessmentId),
                "one row per (transaction, rule) pair evaluated");
        assertEquals(RULES, riskAssessments.countDistinctRules(assessmentId),
                "every rule of the coverage set is present, triggered or not");
        assertEquals(RULES, riskAssessments.findEvaluatedRuleIds(assessmentId).size());

        // Half the rules triggered on one transaction each, at their full 10.00 weight.
        assertEquals(0, new BigDecimal("50.00").compareTo(riskAssessments.totalScore(assessmentId)));
        assertEquals(RULES / 2, riskAssessments.findTriggeredByAssessmentId(assessmentId).size());
        assertEquals(RULES, riskAssessments.summariseRulesForAssessment(assessmentId).size(),
                "the rule-level rollup the analysis page falls back on must see every rule");
    }

    @Test
    @DisplayName("the writer works through the shared, transaction-aware EntityManager the service uses")
    void theWriterWorksThroughTheSharedEntityManagerOfTheService() {
        // RiskAnalysisService does not hold a real EntityManager - it holds the shared proxy, which
        // resolves to the transaction's own one. Unwrapping the Hibernate Session through that proxy
        // is what lets the writer raise the JDBC batch size, so the production wiring is exercised
        // here rather than only the test one.
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        UUID assessmentId = UUID.randomUUID();
        List<RiskAssessment> rows = rows(assessmentId);
        CountingStatementInspector.reset();

        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                assertEquals(TRANSACTIONS * RULES, RiskAssessmentWriter.write(shared, rows)));

        int expectedBatches = (rows.size() + RiskAssessmentWriter.BATCH_SIZE - 1)
                / RiskAssessmentWriter.BATCH_SIZE;
        assertEquals(0, CountingStatementInspector.selects(), "still no read-before-write");
        assertEquals(expectedBatches, CountingStatementInspector.statements(),
                "the batch size must survive the proxy, or the service would insert row by row");
        assertEquals((long) TRANSACTIONS * RULES, riskAssessments.countById_AssessmentId(assessmentId));
    }

    @Test
    @DisplayName("a RUNNING run's progress counters can be moved by the one statement the agent uses")
    void progressCountersAreWritableWhileTheRunIsStillRunning() {
        UUID assessmentId = UUID.randomUUID();
        entityManager.persistAndFlush(AnalysisRun.builder()
                .assessmentId(assessmentId)
                .customer(customer)
                .status(AnalysisStatus.RUNNING)
                .rulesTotal(RULES)
                .rulesEvaluated(0)
                .coverageComplete(false)
                .model("test-model")
                .steps(0)
                .trace("{\"steps\":[]}")
                .requestedBy("operator1")
                .createdAt(NOW)
                .build());
        entityManager.clear();

        // The exact statement RiskAnalysisService.ProgressWriter issues as the run advances. Until it
        // did, the polling fallback showed "0/12 rules, 0 steps" for the whole of an 8-minute run.
        int updated = entityManager.getEntityManager().createQuery("""
                update AnalysisRun run
                set run.steps = :steps, run.rulesEvaluated = :evaluated
                where run.assessmentId = :assessmentId and run.status = :status
                """)
                .setParameter("steps", 7)
                .setParameter("evaluated", 3)
                .setParameter("assessmentId", assessmentId)
                .setParameter("status", AnalysisStatus.RUNNING)
                .executeUpdate();
        entityManager.clear();

        assertEquals(1, updated);
        AnalysisRun reloaded = entityManager.find(AnalysisRun.class, assessmentId);
        assertEquals(7, reloaded.getSteps());
        assertEquals(3, reloaded.getRulesEvaluated());
        assertEquals(AnalysisStatus.RUNNING, reloaded.getStatus(), "progress must not end the run");
    }

    /** Ten rules over the same transactions; the even-numbered ones triggered on one transaction. */
    private List<RiskAssessment> rows(UUID assessmentId) {
        List<RuleOutcome> outcomes = new ArrayList<>(RULES);
        for (int index = 0; index < RULES; index++) {
            boolean triggered = index % 2 == 0;
            List<UUID> matched = triggered ? List.of(transactionIds.get(index)) : List.of();
            outcomes.add(new RuleOutcome(
                    rules.get(index).getRuleId(),
                    rules.get(index).getRuleName(),
                    RuleScope.ALL,
                    new BigDecimal("10.00"),
                    triggered,
                    triggered ? new BigDecimal("10.00") : new BigDecimal("0.00"),
                    triggered ? RuleVerdictSource.AGENT : RuleVerdictSource.DETERMINISTIC_FALLBACK,
                    transactionIds.size(),
                    matched.size(),
                    matched,
                    transactionIds,
                    false, List.of(), "explanation", null, null, null, false));
        }
        return RiskAssessmentRows.build(assessmentId, outcomes, NOW);
    }
}
