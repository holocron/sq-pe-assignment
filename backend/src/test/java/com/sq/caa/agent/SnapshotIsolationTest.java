package com.sq.caa.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.agent.ToolPayloads.TransactionDetail;
import com.sq.caa.agent.ToolPayloads.TransactionPage;
import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.CardActivity;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.Transaction;
import com.sq.caa.repository.CustomerRepository;
import com.sq.caa.repository.TransactionRepository;
import com.sq.caa.rules.EvaluationBatch;
import com.sq.caa.web.dto.TransactionDtos.TransactionView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single-snapshot property of an analysis run, tested against the real database.
 *
 * <p>An analysis loads the customer's activity once into an {@link EvaluationBatch} and every tool
 * reads from it. The property that matters is not that the reads are cheap but that they are
 * <em>one body of evidence</em>: the agent is the sole judge now, so if a tool went back to the
 * database mid-run the model could quote a status, an amount or a merchant that no longer matches
 * the transaction whose id its verdict cites - and the row written to {@code risk_assessments} would
 * evidence something other than what the rationale describes.
 *
 * <p>So the row is deliberately moved underneath a live run: the transaction's status, amount and
 * merchant are updated in the database after the batch is built, and the persistence context is
 * cleared so that any fresh read must go to disk. The live read is asserted to see the new values -
 * that is what proves the update really landed - and the tool is asserted to still answer with the
 * old ones. Serve {@code get_transaction_details} from a repository again and the two assertions
 * become contradictory.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SnapshotIsolationTest {

    private static final Instant WHEN = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            .minus(2, ChronoUnit.HOURS);

    @Autowired private TestEntityManager entityManager;
    @Autowired private CustomerRepository customers;
    @Autowired private TransactionRepository transactions;

    @Test
    @DisplayName("a transaction that changes in the database after the batch is built is still read "
            + "as the run's snapshot, not as the live row")
    void toolReadsStayOnTheRunsSnapshotWhenTheRowMovesUnderneathIt() {
        Customer customer = seedCustomer();
        UUID transactionId = seedCardTransaction(customer);

        RiskAgentTools tools = toolsFor(customer);

        TransactionDetail before = assertInstanceOf(TransactionDetail.class,
                tools.getTransactionDetails(transactionId.toString()));
        assertEquals("Completed", before.status());
        assertEquals(0, new BigDecimal("1200.50").compareTo(before.amount()));
        assertEquals("Globex Online", before.card().merchantName());
        assertEquals("****4321", before.card().cardPan());

        moveTheRowUnderneathTheRun(transactionId);

        // The database really did change: a live read - the very thing the tool used to do - now
        // returns the new values.
        TransactionView live = TransactionView.from(
                transactions.findByIdWithDetails(transactionId).orElseThrow());
        assertEquals("Reversed", live.status());
        assertEquals(0, new BigDecimal("99999.00").compareTo(live.amount()));
        assertEquals("Somewhere Else", live.card().merchantName());

        // The tool does not: it answers from the batch the rule engine was scored against.
        TransactionDetail after = assertInstanceOf(TransactionDetail.class,
                tools.getTransactionDetails(transactionId.toString()));
        assertEquals("Completed", after.status());
        assertEquals(0, new BigDecimal("1200.50").compareTo(after.amount()));
        assertEquals("Globex Online", after.card().merchantName());
        assertEquals("****4321", after.card().cardPan());
        assertEquals("A17X29", after.card().authorizationCode());
        assertEquals(customer.getFullName(), after.customerName());

        // The compact listing reads from the same place, so the two tools cannot disagree either.
        TransactionPage page = assertInstanceOf(TransactionPage.class,
                tools.listTransactions(null, null, null, null, null));
        assertEquals(1, page.returned());
        assertEquals("Completed", page.transactions().getFirst().status());
        assertNotNull(page.transactions().getFirst().counterparty());
        assertTrue(page.transactions().getFirst().counterparty().contains("Globex Online"));
    }

    // ------------------------------------------------------------------

    /** Tools wired exactly as a run wires them, over a batch loaded from the database. */
    private RiskAgentTools toolsFor(Customer customer) {
        EvaluationBatch batch = EvaluationBatch.forCustomer(customer,
                transactions.findAllForCustomerWithDetails(customer.getCustomerId()));
        UUID assessmentId = UUID.randomUUID();
        AgentRunContext context = new AgentRunContext(assessmentId, customer, batch, List.of(),
                AgentTestFixtures.trace(assessmentId));
        return new RiskAgentTools(context, null, null, new StubRuleSqlEvaluator(),
                JsonMapper.builder().build(), 25, 3);
    }

    /**
     * Updates the row behind the analysis and drops the persistence context, so nothing the tools
     * answer with can be a first-level-cache hit and any fresh read has to go to the database.
     */
    private void moveTheRowUnderneathTheRun(UUID transactionId) {
        entityManager.getEntityManager()
                .createQuery("update Transaction t set t.status = :status, t.amount = :amount "
                        + "where t.transactionId = :id")
                .setParameter("status", Transaction.STATUS_REVERSED)
                .setParameter("amount", new BigDecimal("99999.00"))
                .setParameter("id", transactionId)
                .executeUpdate();
        entityManager.getEntityManager()
                .createQuery("update CardActivity c set c.merchantName = :merchant "
                        + "where c.transactionId = :id")
                .setParameter("merchant", "Somewhere Else")
                .setParameter("id", transactionId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    private Customer seedCustomer() {
        Customer customer = customers.save(Customer.builder()
                .customerId(UUID.randomUUID())
                .firstName("Ada")
                .lastName("Lovelace")
                .dob(LocalDate.of(1985, 12, 10))
                .country("GB")
                .build());
        entityManager.flush();
        return customer;
    }

    private UUID seedCardTransaction(Customer customer) {
        Transaction transaction = Transaction.builder()
                .transactionId(UUID.randomUUID())
                .customer(customer)
                .activityType(ActivityType.CARD)
                .amount(new BigDecimal("1200.50"))
                .currency("USD")
                .status(Transaction.STATUS_COMPLETED)
                .createdAt(WHEN)
                .cardActivity(CardActivity.builder()
                        .cardPan("****4321")
                        .cardType("Credit")
                        .merchantName("Globex Online")
                        .mccCode("5732")
                        .cardPresent(false)
                        .authorizationCode("A17X29")
                        .build())
                .build();
        transactions.save(transaction);
        entityManager.flush();
        entityManager.clear();
        return transaction.getTransactionId();
    }
}
