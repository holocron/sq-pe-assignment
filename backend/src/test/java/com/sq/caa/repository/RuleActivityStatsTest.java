package com.sq.caa.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskAssessment;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.domain.Transaction;
import com.sq.caa.repository.projection.RuleActivityStats;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

/**
 * The per-rule activity rollup behind {@code lastFiredAt}/{@code lastJudgedAt} of the rule list:
 * one GROUP BY over {@code risk_assessments} where a positive score marks a firing, a zero score a
 * judgement that did not fire, and a rule with no rows is simply absent.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RuleActivityStatsTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired private CustomerRepository customers;
    @Autowired private TransactionRepository transactions;
    @Autowired private RiskRuleRepository riskRules;
    @Autowired private RiskAssessmentRepository riskAssessments;

    private Customer customer;
    private Transaction transaction;
    private RiskRule firedRule;
    private RiskRule judgedOnlyRule;
    private RiskRule untouchedRule;

    @BeforeEach
    void seedFixture() {
        customer = customers.save(Customer.builder()
                .customerId(UUID.randomUUID())
                .firstName("Ada")
                .lastName("Lovelace")
                .dob(LocalDate.of(1985, 12, 10))
                .country("GB")
                .build());

        transaction = new Transaction();
        transaction.setTransactionId(UUID.randomUUID());
        transaction.setCustomer(customer);
        transaction.setActivityType(ActivityType.CARD);
        transaction.setAmount(new BigDecimal("1200.50"));
        transaction.setCurrency("USD");
        transaction.setStatus(Transaction.STATUS_COMPLETED);
        transaction.setCreatedAt(NOW);
        transactions.save(transaction);

        firedRule = newRule("Stats fired rule ");
        judgedOnlyRule = newRule("Stats judged rule ");
        untouchedRule = newRule("Stats untouched rule ");

        UUID assessmentId = UUID.randomUUID();
        // Each row needs a distinct composite key: (assessmentId, transactionId, ruleId).
        riskAssessments.save(new RiskAssessment(UUID.randomUUID(), transaction.getTransactionId(),
                firedRule.getRuleId(), NOW.minus(2, ChronoUnit.HOURS), new BigDecimal("0.00")));
        riskAssessments.save(new RiskAssessment(UUID.randomUUID(), transaction.getTransactionId(),
                firedRule.getRuleId(), NOW.minus(1, ChronoUnit.HOURS), new BigDecimal("0.00")));
        riskAssessments.save(new RiskAssessment(assessmentId, transaction.getTransactionId(),
                judgedOnlyRule.getRuleId(), NOW.minus(3, ChronoUnit.HOURS), new BigDecimal("0.00")));
    }

    private RiskRule newRule(String namePrefix) {
        return riskRules.save(RiskRule.builder()
                .ruleId(UUID.randomUUID())
                .ruleName(namePrefix + UUID.randomUUID())
                .appliesTo(RuleScope.ALL)
                .thresholdLogic("Fires when the amount is above one thousand.")
                .weight(new BigDecimal("12.50"))
                .build());
    }

    @Test
    void distinguishesFiredJudgedAndUntouchedRules() {
        // A firing recorded after the judgement-only rows: lastFiredAt must catch up to it while
        // lastJudgedAt already covers it.
        riskAssessments.save(new RiskAssessment(UUID.randomUUID(), transaction.getTransactionId(),
                firedRule.getRuleId(), NOW, new BigDecimal("12.50")));

        Map<UUID, RuleActivityStats> stats = riskAssessments.activityStatsByRule().stream()
                .collect(Collectors.toMap(RuleActivityStats::ruleId, Function.identity()));

        RuleActivityStats fired = stats.get(firedRule.getRuleId());
        assertEquals(NOW, fired.lastFiredAt(), "latest row with a positive score");
        assertEquals(NOW, fired.lastJudgedAt(), "latest row whatever the score");

        RuleActivityStats judged = stats.get(judgedOnlyRule.getRuleId());
        assertNull(judged.lastFiredAt(), "judged but never fired");
        assertEquals(NOW.minus(3, ChronoUnit.HOURS), judged.lastJudgedAt());

        assertTrue(!stats.containsKey(untouchedRule.getRuleId()),
                "a rule with no assessment rows is absent - the caller maps that to nulls");
    }

    @Test
    void lastJudgedAtIsTheLatestRowEvenWhenNothingFired() {
        Map<UUID, RuleActivityStats> stats = riskAssessments.activityStatsByRule().stream()
                .collect(Collectors.toMap(RuleActivityStats::ruleId, Function.identity()));

        RuleActivityStats fired = stats.get(firedRule.getRuleId());
        assertNull(fired.lastFiredAt(), "no positive score yet");
        assertEquals(NOW.minus(1, ChronoUnit.HOURS), fired.lastJudgedAt(),
                "the later of the two zero-score judgements");
    }
}
