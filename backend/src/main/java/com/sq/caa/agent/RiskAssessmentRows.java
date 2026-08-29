package com.sq.caa.agent;

import com.sq.caa.domain.RiskAssessment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the settled rule outcomes of a run into {@code risk_assessments} rows.
 *
 * <p>Two requirements meet here and have to be reconciled:
 * <ul>
 *   <li>a row must exist for <b>every (transaction, rule) pair judged</b>, including the rules the
 *       agent found not triggered, so that "no rule was skipped" is provable from the table alone
 *       for every rule that had at least one transaction in scope. In scope means exactly one thing:
 *       the transaction's activity type matches the rule's {@code applies_to}, {@code ALL} matching
 *       every transaction. It is a fact about the data, not a judgement;</li>
 *   <li>a rule's contribution to the score is <b>capped at its weight</b>, however many transactions
 *       it matched - {@code submit_rule_evaluation} clamps the agent's estimate before it is ever
 *       recorded.</li>
 * </ul>
 *
 * <p>Writing the full score on every matched row would break the cap; writing it on only one row
 * would hide which transactions matched. So the score is split across the matched transactions in
 * whole cents, largest remainder first: the sum over a rule is exactly the score the agent gave it,
 * the sum over the run is exactly the run's total score, and every cited transaction still carries a
 * non-zero contribution that marks it as evidence. Transactions in scope that the agent did not
 * cite, and every transaction of a rule it judged as not triggered, get {@code 0.00}.
 *
 * <p>A rule the agent never judged has no {@link RuleOutcome} and therefore writes nothing at all -
 * deliberately. A row of {@code 0.00} for such a rule would be indistinguishable from a rule that
 * was checked and cleared, which is the one thing this table must never say. That run is recorded as
 * {@code FAILED} instead, with the rule named.
 *
 * <p><b>The exact limit of the "provable from the table alone" claim.</b> A row is keyed by a
 * transaction and {@code risk_assessments.transaction_id} is a NOT NULL foreign key, so a rule whose
 * scope contains <em>zero</em> transactions has nothing to key a row on and writes none. That
 * happens for an {@code ALL}-scoped rule when the customer has no activity at all - the rule is
 * genuinely judged, appears in the run's trace and counts towards the coverage counters, but leaves
 * no trace in this table. For such a rule the authoritative record that it was checked is
 * {@code analysis_runs.rules_evaluated} / {@code rules_total} / {@code coverage_complete}, which is
 * written for every run. A sentinel row is not an option without changing the assignment's schema,
 * and the schema is fixed; stating the boundary precisely is. See {@code EmptyScopeCoverageTest}.
 */
public final class RiskAssessmentRows {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private RiskAssessmentRows() {
    }

    /** One row per (transaction, rule) pair judged in the run. */
    public static List<RiskAssessment> build(UUID assessmentId, List<RuleOutcome> outcomes,
            Instant triggeredAt) {
        List<RiskAssessment> rows = new ArrayList<>();
        if (outcomes == null) {
            return rows;
        }
        for (RuleOutcome outcome : outcomes) {
            Map<UUID, BigDecimal> shares = distribute(outcome.score(), outcome.matchedTransactionIds());
            // Matched ids are always in scope; the union is belt and braces so a row can never be
            // dropped and take its score with it.
            Set<UUID> evaluated = new LinkedHashSet<>(outcome.inScopeTransactionIds());
            evaluated.addAll(outcome.matchedTransactionIds());
            for (UUID transactionId : evaluated) {
                rows.add(new RiskAssessment(assessmentId, transactionId, outcome.ruleId(), triggeredAt,
                        shares.getOrDefault(transactionId, ZERO)));
            }
        }
        return rows;
    }

    /**
     * Splits {@code score} into whole cents across {@code matched}, giving the remainder to the
     * first entries. Returns an empty map when there is nothing to distribute.
     */
    public static Map<UUID, BigDecimal> distribute(BigDecimal score, List<UUID> matched) {
        if (score == null || score.signum() <= 0 || matched == null || matched.isEmpty()) {
            return Map.of();
        }
        long cents = score.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        int count = matched.size();
        long base = cents / count;
        long remainder = cents % count;
        Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            shares.put(matched.get(index), BigDecimal.valueOf(base + (index < remainder ? 1 : 0), 2));
        }
        return shares;
    }
}
