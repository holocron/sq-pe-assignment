package com.sq.caa.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.AnalysisRun;
import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.AppUser;
import com.sq.caa.domain.CardActivity;
import com.sq.caa.domain.CryptoActivity;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.DocumentStatus;
import com.sq.caa.domain.KnowledgeDocument;
import com.sq.caa.domain.PaymentActivity;
import com.sq.caa.domain.RiskAssessment;
import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.domain.Transaction;
import com.sq.caa.domain.UserRole;
import com.sq.caa.repository.projection.ActivityTypeAggregate;
import com.sq.caa.repository.projection.AnalysisRunSummary;
import com.sq.caa.repository.projection.CountryCount;
import com.sq.caa.repository.projection.CurrencyCount;
import com.sq.caa.repository.projection.RuleEvaluationRow;
import com.sq.caa.repository.projection.StatusCount;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Verifies that the JPA mappings line up with the Flyway migrations.
 *
 * <p>Booting this slice against the real PostgreSQL instance already exercises Flyway plus
 * {@code ddl-auto=validate}; the assertions then cover the mappings validation cannot see - the
 * native enum types, the shared-primary-key detail rows, the composite key of
 * {@code risk_assessments}, the JSONB trace column and every hand-written query.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistenceVerificationTest {

    @Autowired private TestEntityManager entityManager;
    @Autowired private org.springframework.core.env.Environment environment;
    @Autowired private CustomerRepository customers;
    @Autowired private TransactionRepository transactions;
    @Autowired private CardActivityRepository cardActivities;
    @Autowired private PaymentActivityRepository paymentActivities;
    @Autowired private CryptoActivityRepository cryptoActivities;
    @Autowired private RiskRuleRepository riskRules;
    @Autowired private RiskAssessmentRepository riskAssessments;
    @Autowired private AnalysisRunRepository analysisRuns;
    @Autowired private KnowledgeDocumentRepository knowledgeDocuments;
    @Autowired private AppUserRepository appUsers;

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private Customer customer;
    private Transaction cardTx;
    private Transaction paymentTx;
    private Transaction cryptoTx;
    private RiskRule rule;

    @BeforeEach
    void seedFixture() {
        customer = customers.save(Customer.builder()
                .customerId(UUID.randomUUID())
                .firstName("Ada")
                .lastName("Lovelace")
                .dob(LocalDate.of(1985, 12, 10))
                .country("GB")
                .build());

        cardTx = newTransaction(ActivityType.CARD, "1200.50", "USD", Transaction.STATUS_COMPLETED,
                NOW.minus(2, ChronoUnit.HOURS));
        cardTx.setCardActivity(CardActivity.builder()
                .cardPan("****4321")
                .cardType("Credit")
                .merchantName("Globex Online")
                .mccCode("5732")
                .cardPresent(false)
                .authorizationCode("A17X29")
                .declineReason("Do not honour")
                .build());

        paymentTx = newTransaction(ActivityType.PAYMENT, "9800.00", "USD", Transaction.STATUS_FAILED,
                NOW.minus(5, ChronoUnit.HOURS));
        paymentTx.setPaymentActivity(PaymentActivity.builder()
                .paymentMethod("SWIFT")
                .senderAccount("GB29NWBK60161331926819")
                .receiverAccount("IR580540105180021273113007")
                .receiverBankCountry("IR")
                .build());

        cryptoTx = newTransaction(ActivityType.CRYPTO, "45000.00", "XMR", Transaction.STATUS_COMPLETED,
                NOW.minus(30, ChronoUnit.DAYS));
        cryptoTx.setCryptoActivity(CryptoActivity.builder()
                .blockchain("XMR")
                .walletAddressFrom("4Awall3tFrom")
                .walletAddressTo("4Awall3tTo")
                .txHash("0xfeed")
                .exchangeName(null)
                .build());

        transactions.saveAll(List.of(cardTx, paymentTx, cryptoTx));

        rule = riskRules.save(RiskRule.builder()
                .ruleId(UUID.randomUUID())
                .ruleName("Verification rule " + UUID.randomUUID())
                .appliesTo(RuleScope.ALL)
                .thresholdLogic("{\"field\":\"amount\",\"operator\":\"GT\",\"value\":1000}")
                .weight(new BigDecimal("12.50"))
                .build());

        entityManager.flush();
        entityManager.clear();
    }

    private Transaction newTransaction(ActivityType type, String amount, String currency, String status,
            Instant createdAt) {
        Transaction tx = new Transaction();
        tx.setTransactionId(UUID.randomUUID());
        tx.setCustomer(customer);
        tx.setActivityType(type);
        tx.setAmount(new BigDecimal(amount));
        tx.setCurrency(currency);
        tx.setStatus(status);
        tx.setCreatedAt(createdAt);
        return tx;
    }

    @Test
    void nativeEnumsAndSharedPrimaryKeysRoundTrip() {
        Transaction reloaded = transactions.findByIdWithDetails(cardTx.getTransactionId()).orElseThrow();
        assertEquals(ActivityType.CARD, reloaded.getActivityType());
        assertEquals("GB", reloaded.getCustomer().getCountry());
        assertNotNull(reloaded.getCardActivity());
        assertEquals(cardTx.getTransactionId(), reloaded.getCardActivity().getTransactionId());
        assertEquals("Do not honour", reloaded.getCardActivity().getDeclineReason());
        assertEquals(NOW.minus(2, ChronoUnit.HOURS), reloaded.getCreatedAt());

        assertEquals("IR", paymentActivities.findById(paymentTx.getTransactionId())
                .orElseThrow().getReceiverBankCountry());
        assertEquals("XMR", cryptoActivities.findById(cryptoTx.getTransactionId())
                .orElseThrow().getBlockchain());
        assertEquals(1, cardActivities.findDeclinedForCustomer(customer.getCustomerId()).size());
        assertEquals(1, cryptoActivities.findUnattributedForCustomer(customer.getCustomerId()).size());
        assertEquals(1, paymentActivities
                .findForCustomerByReceiverBankCountry(customer.getCustomerId(), Set.of("IR", "KP")).size());
        assertEquals(3, cardActivities.findByTransaction_TransactionIdIn(
                List.of(cardTx.getTransactionId())).size()
                + paymentActivities.findByTransaction_TransactionIdIn(
                        List.of(paymentTx.getTransactionId())).size()
                + cryptoActivities.findByTransaction_TransactionIdIn(
                        List.of(cryptoTx.getTransactionId())).size());
    }

    @Test
    void customerSearchMatchesNameAndId() {
        PageRequest page = PageRequest.of(0, 20);
        assertTrue(customers.search("lovelace", page).getContent().stream()
                .anyMatch(c -> c.getCustomerId().equals(customer.getCustomerId())));
        assertTrue(customers.search("Ada Love", page).getContent().stream()
                .anyMatch(c -> c.getCustomerId().equals(customer.getCustomerId())));
        assertTrue(customers.search(customer.getCustomerId().toString(), page).getContent().stream()
                .anyMatch(c -> c.getCustomerId().equals(customer.getCustomerId())));
        assertFalse(customers.search(null, page).isEmpty());
        assertEquals(0, customers.search("no-such-customer-xyz", page).getTotalElements());
    }

    @Test
    void activityFiltersAreAllOptional() {
        PageRequest page = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        UUID id = customer.getCustomerId();

        assertEquals(3, transactions.findForCustomer(id, null, null, null, null, page).getTotalElements());
        assertEquals(1, transactions
                .findForCustomer(id, ActivityType.PAYMENT, null, null, null, page).getTotalElements());
        assertEquals(2, transactions
                .findForCustomer(id, null, "completed", null, null, page).getTotalElements());
        assertEquals(2, transactions
                .findForCustomer(id, null, null, NOW.minus(1, ChronoUnit.DAYS), null, page).getTotalElements());
        assertEquals(1, transactions
                .findForCustomer(id, null, null, null, NOW.minus(1, ChronoUnit.DAYS), page).getTotalElements());
        assertEquals(1, transactions.findForCustomer(id, ActivityType.CARD, "Completed",
                NOW.minus(1, ChronoUnit.DAYS), NOW, page).getTotalElements());

        Page<Transaction> withDetails =
                transactions.findForCustomerWithDetails(id, null, null, null, null, null, null, page);
        assertEquals(3, withDetails.getTotalElements());
        assertEquals(3, withDetails.getContent().size());
        assertTrue(withDetails.getContent().stream()
                .anyMatch(t -> t.getPaymentActivity() != null
                        && "SWIFT".equals(t.getPaymentActivity().getPaymentMethod())));

        assertEquals(3, transactions.findAllForCustomerWithDetails(id).size());
        assertEquals(3, transactions.findByCustomer_CustomerIdOrderByCreatedAtDesc(id).size());
        assertEquals(3, transactions.countByCustomer_CustomerId(id));
    }

    @Test
    void aggregatesGroupPerActivityTypeStatusCurrencyAndCountry() {
        UUID id = customer.getCustomerId();

        List<ActivityTypeAggregate> perType = transactions.aggregateByActivityType(id);
        assertEquals(3, perType.size());
        ActivityTypeAggregate card = perType.stream()
                .filter(a -> a.getActivityType() == ActivityType.CARD).findFirst().orElseThrow();
        assertEquals(1L, card.getTxCount());
        assertEquals(0, new BigDecimal("1200.50").compareTo(card.getTotalAmount()));
        assertEquals(0, new BigDecimal("1200.50").compareTo(card.getMaxAmount()));
        assertNotNull(card.getAvgAmount());
        assertNotNull(card.getFirstAt());
        assertNotNull(card.getLastAt());

        assertEquals(Set.of(ActivityType.CARD, ActivityType.PAYMENT, ActivityType.CRYPTO),
                Set.copyOf(transactions.findDistinctActivityTypes(id)));

        List<StatusCount> byStatus = transactions.aggregateByStatus(id);
        assertEquals(2, byStatus.size());
        assertEquals(2L, byStatus.stream()
                .filter(s -> Transaction.STATUS_COMPLETED.equals(s.getStatus()))
                .findFirst().orElseThrow().getTxCount());

        List<CurrencyCount> byCurrency = transactions.aggregateByCurrency(id);
        assertEquals(2, byCurrency.size());

        List<CountryCount> byCountry = transactions.aggregateByReceiverBankCountry(id);
        assertEquals(1, byCountry.size());
        assertEquals("IR", byCountry.getFirst().getCountry());

        assertEquals(0, new BigDecimal("56000.50").compareTo(transactions.sumAmountForCustomer(id)));
    }

    @Test
    void windowAggregatesBackTheRuleDslFields() {
        UUID id = customer.getCustomerId();
        Instant dayAgo = NOW.minus(1, ChronoUnit.DAYS);

        assertEquals(2, transactions.countInWindow(id, dayAgo, NOW));
        assertEquals(0, new BigDecimal("11000.50").compareTo(transactions.sumAmountInWindow(id, dayAgo, NOW)));
        assertEquals(0, new BigDecimal("9800.00").compareTo(transactions.maxAmountInWindow(id, dayAgo, NOW)));
        assertEquals(1, transactions.countByStatusInWindow(id, "failed", dayAgo, NOW));
        assertEquals(1, transactions.countByActivityTypeInWindow(id, ActivityType.CARD, dayAgo, NOW));
        assertEquals(1, transactions.countDistinctReceiverCountriesInWindow(id,
                NOW.minus(90, ChronoUnit.DAYS), NOW));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                transactions.sumAmountInWindow(id, NOW.plusSeconds(1), NOW.plusSeconds(2))));
    }

    @Test
    void riskRuleCoverageSetHonoursScope() {
        RiskRule cryptoOnly = riskRules.save(RiskRule.builder()
                .ruleId(UUID.randomUUID())
                .ruleName("Crypto only " + UUID.randomUUID())
                .appliesTo(RuleScope.CRYPTO)
                .thresholdLogic("{\"field\":\"crypto.blockchain\",\"operator\":\"EQ\",\"value\":\"XMR\"}")
                .weight(new BigDecimal("30.00"))
                .build());
        entityManager.flush();
        entityManager.clear();

        List<UUID> cardOnlyCoverage = riskRules.findCoverageSet(List.of(ActivityType.CARD)).stream()
                .map(RiskRule::getRuleId).toList();
        assertTrue(cardOnlyCoverage.contains(rule.getRuleId()));
        assertFalse(cardOnlyCoverage.contains(cryptoOnly.getRuleId()));

        List<UUID> cryptoCoverage = riskRules.findCoverageSet(List.of(ActivityType.CRYPTO)).stream()
                .map(RiskRule::getRuleId).toList();
        assertTrue(cryptoCoverage.contains(cryptoOnly.getRuleId()));
        assertTrue(cryptoCoverage.contains(rule.getRuleId()));

        assertEquals(RuleScope.CRYPTO,
                riskRules.findById(cryptoOnly.getRuleId()).orElseThrow().getAppliesTo());
        assertTrue(riskRules.existsByRuleNameIgnoreCase(cryptoOnly.getRuleName().toUpperCase()));
    }

    @Test
    void riskAssessmentsShareOneAssessmentIdAcrossTransactionsAndRules() {
        UUID assessmentId = UUID.randomUUID();
        riskAssessments.saveAll(List.of(
                new RiskAssessment(assessmentId, cardTx.getTransactionId(), rule.getRuleId(), NOW,
                        new BigDecimal("12.50")),
                new RiskAssessment(assessmentId, paymentTx.getTransactionId(), rule.getRuleId(), NOW,
                        BigDecimal.ZERO),
                new RiskAssessment(assessmentId, cryptoTx.getTransactionId(), rule.getRuleId(), NOW,
                        new BigDecimal("12.50"))));
        entityManager.flush();
        entityManager.clear();

        assertEquals(3, riskAssessments.findById_AssessmentId(assessmentId).size());
        assertEquals(3, riskAssessments.countById_AssessmentId(assessmentId));
        assertEquals(1, riskAssessments.findById_TransactionId(cardTx.getTransactionId()).size());
        assertEquals(1, riskAssessments.countDistinctRules(assessmentId));
        assertEquals(List.of(rule.getRuleId()), riskAssessments.findEvaluatedRuleIds(assessmentId));
        assertEquals(0, new BigDecimal("25.00").compareTo(riskAssessments.totalScore(assessmentId)));
        assertEquals(0, BigDecimal.ZERO.compareTo(riskAssessments.totalScore(UUID.randomUUID())));

        List<RiskAssessment> detailed = riskAssessments.findDetailedByAssessmentId(assessmentId);
        assertEquals(3, detailed.size());
        assertEquals(rule.getRuleName(), detailed.getFirst().getRule().getRuleName());
        assertTrue(detailed.getFirst().isTriggered());
        assertEquals(2, riskAssessments.findTriggeredByAssessmentId(assessmentId).size());

        List<RuleEvaluationRow> rollup = riskAssessments.summariseRulesForAssessment(assessmentId);
        assertEquals(1, rollup.size());
        RuleEvaluationRow row = rollup.getFirst();
        assertEquals(rule.getRuleId(), row.getRuleId());
        assertEquals(RuleScope.ALL, row.getAppliesTo());
        assertEquals(3L, row.getEvaluatedCount());
        assertEquals(2L, row.getTriggeredCount());
        assertEquals(0, new BigDecimal("25.00").compareTo(row.getScore()));

        assertEquals(1, riskAssessments.deleteByAssessmentIdAndRuleId(assessmentId, rule.getRuleId())
                > 0 ? 1 : 0);
        assertEquals(0, riskAssessments.countById_AssessmentId(assessmentId));
    }

    @Test
    void analysisRunPersistsJsonbTraceAndOrdersHistoryNewestFirst() {
        AnalysisRun older = analysisRuns.save(AnalysisRun.builder()
                .assessmentId(UUID.randomUUID())
                .customer(customer)
                .status(AnalysisStatus.COMPLETED)
                .riskLevel(RiskLevel.MEDIUM)
                .totalScore(new BigDecimal("31.00"))
                .summary("older run")
                .recommendations("monitor")
                .rulesTotal(4)
                .rulesEvaluated(4)
                .coverageComplete(true)
                .model("gpt-oss-120b")
                .steps(7)
                .durationMs(4200L)
                .trace("{\"steps\":[{\"n\":1,\"type\":\"tool_call\",\"tool\":\"list_risk_rules\"}]}")
                .requestedBy("operator1")
                .createdAt(NOW.minus(3, ChronoUnit.HOURS))
                .completedAt(NOW.minus(3, ChronoUnit.HOURS).plusSeconds(4))
                .build());

        AnalysisRun newer = analysisRuns.save(AnalysisRun.builder()
                .assessmentId(UUID.randomUUID())
                .customer(customer)
                .status(AnalysisStatus.RUNNING)
                .rulesTotal(4)
                .rulesEvaluated(0)
                .coverageComplete(false)
                .steps(0)
                .trace("{\"steps\":[]}")
                .requestedBy("admin")
                .createdAt(NOW)
                .build());
        entityManager.flush();
        entityManager.clear();

        AnalysisRun loaded = analysisRuns.findByIdWithCustomer(older.getAssessmentId()).orElseThrow();
        assertEquals(RiskLevel.MEDIUM, loaded.getRiskLevel());
        assertEquals(AnalysisStatus.COMPLETED, loaded.getStatus());
        assertTrue(loaded.getTrace().contains("list_risk_rules"));
        assertTrue(loaded.isCoverageComplete());
        assertEquals(4200L, loaded.getDurationMs());
        assertEquals(customer.getCustomerId(), loaded.getCustomer().getCustomerId());

        List<AnalysisRun> history = analysisRuns.findByCustomerOrderByCreatedAtDesc(customer.getCustomerId());
        assertEquals(2, history.size());
        assertEquals(newer.getAssessmentId(), history.getFirst().getAssessmentId());

        List<AnalysisRunSummary> summaries = analysisRuns.findSummaries(customer.getCustomerId());
        assertEquals(2, summaries.size());
        assertEquals(newer.getAssessmentId(), summaries.getFirst().getAssessmentId());
        assertEquals("Lovelace", summaries.getFirst().getCustomerLastName());
        assertFalse(analysisRuns.findSummaries(null).isEmpty());

        assertFalse(analysisRuns.findAllOrderByCreatedAtDesc(PageRequest.of(0, 5)).isEmpty());
        assertTrue(analysisRuns.findByStatusOrderByCreatedAtAsc(AnalysisStatus.RUNNING).stream()
                .anyMatch(a -> a.getAssessmentId().equals(newer.getAssessmentId())));
        assertEquals(2, analysisRuns.countByCustomer_CustomerId(customer.getCustomerId()));
        assertEquals(newer.getAssessmentId(),
                analysisRuns.findFirstByCustomer_CustomerIdOrderByCreatedAtDesc(customer.getCustomerId())
                        .orElseThrow().getAssessmentId());

        assertEquals(RiskLevel.HIGH, RiskLevel.forScore(new BigDecimal("50.00")));
        assertEquals(RiskLevel.CRITICAL, RiskLevel.forScore(new BigDecimal("90")));
        assertEquals(RiskLevel.LOW, RiskLevel.forScore(null));
    }

    @Test
    void appUsersAndKnowledgeDocumentsRoundTrip() {
        String username = "verify-" + UUID.randomUUID();
        appUsers.save(AppUser.builder()
                .userId(UUID.randomUUID())
                .username(username)
                .passwordHash("$2a$10$abcdefghijklmnopqrstuv")
                .fullName("Verification User")
                .role(UserRole.ADMIN)
                .enabled(true)
                .createdAt(NOW)
                .build());

        KnowledgeDocument document = knowledgeDocuments.save(KnowledgeDocument.builder()
                .documentId(UUID.randomUUID())
                .filename("aml-policy.pdf")
                .title("AML Policy")
                .mimeType("application/pdf")
                .sizeBytes(24_576L)
                .chunkCount(12)
                .status(DocumentStatus.INDEXED)
                .uploadedBy("admin")
                .uploadedAt(NOW)
                .build());
        entityManager.flush();
        entityManager.clear();

        AppUser loadedUser = appUsers.findByUsername(username).orElseThrow();
        assertEquals(UserRole.ADMIN, loadedUser.getRole());
        assertEquals("ROLE_ADMIN", loadedUser.getRole().authority());
        assertTrue(loadedUser.isEnabled());
        assertTrue(appUsers.existsByUsernameIgnoreCase(username.toUpperCase()));
        assertFalse(appUsers.findByRoleOrderByUsernameAsc(UserRole.ADMIN).isEmpty());
        assertFalse(appUsers.findAllByOrderByUsernameAsc().isEmpty());

        KnowledgeDocument loadedDocument = knowledgeDocuments.findById(document.getDocumentId()).orElseThrow();
        assertEquals(DocumentStatus.INDEXED, loadedDocument.getStatus());
        assertEquals(12, loadedDocument.getChunkCount());
        assertTrue(knowledgeDocuments.existsByFilenameIgnoreCase("AML-POLICY.PDF"));
        assertFalse(knowledgeDocuments.findByStatusOrderByUploadedAtDesc(DocumentStatus.INDEXED).isEmpty());
        assertFalse(knowledgeDocuments.findAllByOrderByUploadedAtDesc().isEmpty());
    }
    // -------------------------------------------------------------------------
    // Independent hardening probes: the traps that ddl-auto=validate cannot see.
    // -------------------------------------------------------------------------

    @Test
    void instantsAreStoredAsUtcWallClockInTimestampColumns() {
        Instant fixed = Instant.parse("2026-01-15T23:30:00Z");
        Transaction tx = newTransaction(ActivityType.CARD, "10.00", "USD", Transaction.STATUS_COMPLETED, fixed);
        tx.setCardActivity(CardActivity.builder()
                .cardPan("****0001")
                .cardType("Debit")
                .merchantName("UTC Probe")
                .mccCode("5411")
                .cardPresent(true)
                .authorizationCode("UTC001")
                .build());
        transactions.save(tx);
        entityManager.flush();
        entityManager.clear();

        Object raw = entityManager.getEntityManager()
                .createNativeQuery("select to_char(created_at, 'YYYY-MM-DD\"T\"HH24:MI:SS') "
                        + "from transactions where transaction_id = :id")
                .setParameter("id", tx.getTransactionId())
                .getSingleResult();
        assertEquals("2026-01-15T23:30:00", raw,
                "created_at must hold the UTC wall clock, not the JVM local time");
        assertEquals(fixed, transactions.findById(tx.getTransactionId()).orElseThrow().getCreatedAt());
    }

    @Test
    void builderWiresDetailBackReferencesLikeTheSetters() {
        Transaction tx = Transaction.builder()
                .transactionId(UUID.randomUUID())
                .customer(customer)
                .activityType(ActivityType.PAYMENT)
                .amount(new BigDecimal("9500.00"))
                .currency("USD")
                .status(Transaction.STATUS_COMPLETED)
                .createdAt(NOW.minus(1, ChronoUnit.HOURS))
                .paymentActivity(PaymentActivity.builder()
                        .paymentMethod("ACH")
                        .senderAccount("US11112222")
                        .receiverAccount("US33334444")
                        .receiverBankCountry("US")
                        .build())
                .build();
        assertNotNull(tx.getPaymentActivity().getTransaction(),
                "Transaction.builder() must wire the shared-key back-reference");

        transactions.save(tx);
        entityManager.flush();
        entityManager.clear();

        Transaction reloaded = transactions.findByIdWithDetails(tx.getTransactionId()).orElseThrow();
        assertNotNull(reloaded.getPaymentActivity());
        assertEquals("ACH", reloaded.getPaymentActivity().getPaymentMethod());
        assertEquals(tx.getTransactionId(), reloaded.getPaymentActivity().getTransactionId());
    }

    @Test
    void charColumnsComeBackWithoutPadding() {
        Customer loaded = customers.findById(customer.getCustomerId()).orElseThrow();
        assertEquals(2, loaded.getCountry().length());
        assertEquals("GB", loaded.getCountry());
        assertEquals("Ada Lovelace", loaded.getFullName());
        assertNotNull(loaded.getAge());

        PaymentActivity payment = paymentActivities.findById(paymentTx.getTransactionId()).orElseThrow();
        assertEquals(2, payment.getReceiverBankCountry().length());

        List<CountryCount> byCountry =
                transactions.aggregateByReceiverBankCountry(customer.getCustomerId());
        assertTrue(byCountry.stream().allMatch(c -> c.getCountry().length() == 2));
    }

    @Test
    void documentChunksSatisfiesThePgVectorStoreContract() {
        @SuppressWarnings("unchecked")
        List<Object[]> columns = entityManager.getEntityManager()
                .createNativeQuery("""
                        select column_name, data_type
                        from information_schema.columns
                        where table_schema = 'public' and table_name = 'document_chunks'
                        """)
                .getResultList();
        Set<String> names = columns.stream().map(row -> (String) row[0]).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of("id", "content", "metadata", "embedding")),
                "PgVectorSchemaValidator requires id/content/metadata/embedding, found " + names);

        Object dimensions = entityManager.getEntityManager()
                .createNativeQuery("""
                        select a.atttypmod
                        from pg_attribute a
                        join pg_class c on a.attrelid = c.oid
                        join pg_namespace n on c.relnamespace = n.oid
                        where n.nspname = 'public' and c.relname = 'document_chunks'
                          and a.attname = 'embedding' and a.attnum > 0 and not a.attisdropped
                        """)
                .getSingleResult();
        // The embedding model is a runtime setting: when an admin has saved one, the column
        // length follows llm_settings.embed_dimension; otherwise the environment default applies.
        List<?> settingsDims = entityManager.getEntityManager()
                .createNativeQuery("select embed_dimension from llm_settings")
                .getResultList();
        int vectorDimension = settingsDims.isEmpty()
                ? Integer.parseInt(
                        environment.getProperty("spring.ai.vectorstore.pgvector.dimensions", "2560"))
                : ((Number) settingsDims.get(0)).intValue();
        assertEquals(vectorDimension, ((Number) dimensions).intValue());

        // The exact upsert PgVectorStore issues, including the ?::jsonb cast into a json column.
        UUID chunkId = UUID.randomUUID();
        String embedding = "[" + String.join(",", Collections.nCopies(vectorDimension, "0.01")) + "]";
        entityManager.getEntityManager()
                .createNativeQuery("""
                        insert into document_chunks (id, content, metadata, embedding)
                        values (:id, :content, cast(:metadata as jsonb), cast(:embedding as vector))
                        on conflict (id) do update set content = :content,
                                                       metadata = cast(:metadata as jsonb),
                                                       embedding = cast(:embedding as vector)
                        """)
                .setParameter("id", chunkId)
                .setParameter("content", "policy chunk")
                .setParameter("metadata", "{\"document_id\":\"" + UUID.randomUUID() + "\",\"chunk_index\":0}")
                .setParameter("embedding", embedding)
                .executeUpdate();

        Object distance = entityManager.getEntityManager()
                .createNativeQuery("select embedding <=> cast(:embedding as vector) from document_chunks where id = :id")
                .setParameter("embedding", embedding)
                .setParameter("id", chunkId)
                .getSingleResult();
        assertEquals(0.0d, ((Number) distance).doubleValue(), 1e-9);

        entityManager.getEntityManager()
                .createNativeQuery("delete from document_chunks where id = :id")
                .setParameter("id", chunkId)
                .executeUpdate();
    }
    @Test
    void pagedCountQueriesExecute() {
        // A page whose content is shorter than its size never triggers the count query, so every
        // paged finder is probed here with size 1 to force the derived/explicit count statement out.
        UUID id = customer.getCustomerId();
        PageRequest one = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt"));

        assertEquals(3, transactions.findForCustomer(id, null, null, null, null, one).getTotalElements());
        assertEquals(3, transactions.findForCustomerWithDetails(id, null, null, null, null, null, null, one)
                .getTotalElements());
        assertEquals(1, transactions.findForCustomer(id, ActivityType.CRYPTO, null, null, null, one)
                .getTotalElements());

        analysisRuns.save(AnalysisRun.builder()
                .assessmentId(UUID.randomUUID())
                .customer(customer)
                .status(AnalysisStatus.RUNNING)
                .rulesTotal(1)
                .rulesEvaluated(0)
                .coverageComplete(false)
                .steps(0)
                .createdAt(NOW)
                .build());
        entityManager.flush();
        assertTrue(analysisRuns.findAllOrderByCreatedAtDesc(PageRequest.of(0, 1)).getTotalElements() >= 1);

        assertTrue(customers.search("lovelace", PageRequest.of(0, 1)).getTotalElements() >= 1);
        assertTrue(customers.search(null, PageRequest.of(0, 1)).getTotalElements() >= 1);
    }
    @Test
    void traceIsStoredAsARealJsonbObjectNotAQuotedString() {
        UUID assessmentId = UUID.randomUUID();
        analysisRuns.save(AnalysisRun.builder()
                .assessmentId(assessmentId)
                .customer(customer)
                .status(AnalysisStatus.COMPLETED)
                .riskLevel(RiskLevel.HIGH)
                .totalScore(new BigDecimal("55.00"))
                .rulesTotal(2)
                .rulesEvaluated(2)
                .coverageComplete(true)
                .steps(3)
                .trace("{\"steps\":[{\"n\":1,\"type\":\"tool_call\",\"tool\":\"list_risk_rules\"},"
                        + "{\"n\":2,\"type\":\"final\",\"risk_level\":\"HIGH\"}]}")
                .createdAt(NOW)
                .build());
        entityManager.flush();
        entityManager.clear();

        Object type = entityManager.getEntityManager()
                .createNativeQuery("select jsonb_typeof(trace) from analysis_runs where assessment_id = :id")
                .setParameter("id", assessmentId)
                .getSingleResult();
        assertEquals("object", type, "trace must be a JSONB object so the UI can query it, not a JSON string");

        Object steps = entityManager.getEntityManager()
                .createNativeQuery("select jsonb_array_length(trace -> 'steps') from analysis_runs "
                        + "where assessment_id = :id")
                .setParameter("id", assessmentId)
                .getSingleResult();
        assertEquals(2, ((Number) steps).intValue());

        Object tool = entityManager.getEntityManager()
                .createNativeQuery("select trace -> 'steps' -> 0 ->> 'tool' from analysis_runs "
                        + "where assessment_id = :id")
                .setParameter("id", assessmentId)
                .getSingleResult();
        assertEquals("list_risk_rules", tool);

        assertTrue(analysisRuns.findById(assessmentId).orElseThrow().getTrace().contains("\"steps\""));
    }
    @Test
    void specificationsCanFilterOnTheNativeEnumAndOnAmount() {
        // The agent's list_transactions tool also filters by min_amount, which is not one of the
        // named finders; it composes a Specification instead. Criteria queries have to bind the
        // native activity_type enum correctly for that to work.
        UUID id = customer.getCustomerId();

        Specification<Transaction> ofCustomer = (root, query, cb) ->
                cb.equal(root.get("customer").get("customerId"), id);
        Specification<Transaction> crypto = (root, query, cb) ->
                cb.equal(root.get("activityType"), ActivityType.CRYPTO);
        Specification<Transaction> large = (root, query, cb) ->
                cb.greaterThanOrEqualTo(root.get("amount"), new BigDecimal("10000.00"));
        Specification<Transaction> completed = (root, query, cb) ->
                cb.equal(cb.lower(root.get("status")), Transaction.STATUS_COMPLETED.toLowerCase());

        assertEquals(3, transactions.count(ofCustomer));
        assertEquals(1, transactions.count(ofCustomer.and(crypto)));
        assertEquals(1, transactions.count(ofCustomer.and(large)));
        assertEquals(0, transactions.count(ofCustomer.and(crypto).and(large).and(completed)
                .and((root, query, cb) -> cb.equal(root.get("currency"), "USD"))));

        Page<Transaction> page = transactions.findAll(ofCustomer.and(completed),
                PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "amount")));
        assertEquals(2, page.getTotalElements());
        assertEquals(ActivityType.CRYPTO, page.getContent().getFirst().getActivityType());
    }
}
