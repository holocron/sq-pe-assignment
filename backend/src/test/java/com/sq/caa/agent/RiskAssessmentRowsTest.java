package com.sq.caa.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.RiskAssessment;
import com.sq.caa.domain.RuleScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code risk_assessments} rows a run writes.
 *
 * <p>Two invariants are load-bearing and are asserted here: one row exists for every (transaction,
 * rule) pair the agent judged - including the rules it found not triggered, at {@code 0.00} - and
 * the score contributed by a rule sums to exactly the score the agent gave it, which
 * {@code submit_rule_evaluation} has already capped at the rule's weight, whatever the number of
 * transactions cited.
 *
 * <p>A rule the agent never judged has no outcome and so appears nowhere below: that case is the
 * subject of {@link RuleCoverageGuaranteeTest}, and it is why a partial run fails rather than
 * writing rows that would look like a completed check.
 */
class RiskAssessmentRowsTest {

    private static final Instant AT = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    @DisplayName("a triggered rule's weight is split across its matches and sums to the weight")
    void triggeredRuleSumsToItsWeight() {
        List<UUID> inScope = ids(5);
        List<UUID> matched = inScope.subList(0, 3);
        RuleOutcome outcome = outcome("Sanctioned wire", "30.00", true, "30.00", matched, inScope);

        List<RiskAssessment> rows = RiskAssessmentRows.build(UUID.randomUUID(), List.of(outcome), AT);

        assertEquals(5, rows.size(), "one row per (transaction, rule) pair judged");
        assertEquals(0, new BigDecimal("30.00").compareTo(sum(rows)));
        assertEquals(3, rows.stream().filter(RiskAssessment::isTriggered).count());
        assertEquals(2, rows.stream().filter(row -> !row.isTriggered()).count());
    }

    @Test
    @DisplayName("an indivisible weight keeps its cents: the remainder goes to the first matches")
    void indivisibleWeightIsDistributedWithoutLoss() {
        List<UUID> inScope = ids(3);
        RuleOutcome outcome = outcome("Structuring", "20.00", true, "20.00", inScope, inScope);

        List<RiskAssessment> rows = RiskAssessmentRows.build(UUID.randomUUID(), List.of(outcome), AT);

        assertEquals(0, new BigDecimal("20.00").compareTo(sum(rows)));
        assertEquals(0, new BigDecimal("6.67").compareTo(rows.get(0).getScoreContribution()));
        assertEquals(0, new BigDecimal("6.67").compareTo(rows.get(1).getScoreContribution()));
        assertEquals(0, new BigDecimal("6.66").compareTo(rows.get(2).getScoreContribution()));
    }

    @Test
    @DisplayName("a rule that did not trigger is still written, at 0.00, for every transaction in scope")
    void untriggeredRuleIsStillPersisted() {
        List<UUID> inScope = ids(4);
        RuleOutcome outcome = outcome("Card decline burst", "10.00", false, "0.00", List.of(), inScope);

        List<RiskAssessment> rows = RiskAssessmentRows.build(UUID.randomUUID(), List.of(outcome), AT);

        assertEquals(4, rows.size(), "a skipped rule would leave no rows, which is what must be provable");
        assertEquals(0, BigDecimal.ZERO.compareTo(sum(rows)));
        assertTrue(rows.stream().noneMatch(RiskAssessment::isTriggered));
    }

    @Test
    @DisplayName("a rule with no transactions in scope writes no rows and costs nothing")
    void ruleWithNothingInScopeWritesNothing() {
        RuleOutcome outcome = outcome("Crypto rule", "15.00", false, "0.00", List.of(), List.of());
        assertTrue(RiskAssessmentRows.build(UUID.randomUUID(), List.of(outcome), AT).isEmpty());
    }

    @Test
    @DisplayName("the whole run's rows sum to the run's total score")
    void runTotalIsTheSumOfTheColumn() {
        List<UUID> payments = ids(4);
        List<UUID> cards = ids(3);
        List<RuleOutcome> outcomes = List.of(
                outcome("Sanctioned wire", "30.00", true, "30.00", payments.subList(0, 1), payments),
                outcome("Structuring", "20.00", true, "20.00", payments.subList(1, 4), payments),
                outcome("Card decline burst", "10.00", false, "0.00", List.of(), cards));

        List<RiskAssessment> rows = RiskAssessmentRows.build(UUID.randomUUID(), outcomes, AT);

        assertEquals(4 + 4 + 3, rows.size());
        assertEquals(0, new BigDecimal("50.00").compareTo(sum(rows)));
    }

    // ------------------------------------------------------------------

    private static RuleOutcome outcome(String name, String weight, boolean triggered, String score,
            List<UUID> matched, List<UUID> inScope) {
        return new RuleOutcome(UUID.randomUUID(), name, RuleScope.ALL, new BigDecimal(weight), triggered,
                new BigDecimal(score), RuleVerdictSource.AGENT_JUDGED, inScope.size(), matched.size(),
                matched, inScope, "The agent judged " + name + " from the evidence it cited.", null,
                false);
    }

    private static List<UUID> ids(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(index -> UUID.randomUUID()).toList();
    }

    private static BigDecimal sum(List<RiskAssessment> rows) {
        return rows.stream().map(RiskAssessment::getScoreContribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
