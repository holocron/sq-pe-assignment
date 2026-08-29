package com.sq.caa.agent;

import com.sq.caa.domain.RuleScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One rule of one run as the agent settled it: the verdict, the evidence behind it and the reasoning
 * that a reviewer reads.
 *
 * <p>An outcome exists only for a rule the agent actually judged. That is deliberate: a rule with no
 * outcome was not evaluated, and a run in that state cannot be reported as complete - it is
 * persisted {@code FAILED} with the unjudged rules named. Writing a placeholder outcome would put a
 * "not triggered, 0.00" row into {@code risk_assessments} for a rule nobody ever looked at, which is
 * exactly the false assurance the coverage guarantee exists to prevent.
 *
 * @param score                  the agent's estimate after clamping to {@link #weight()}
 * @param claimedScore           what the model asked for before clamping, or {@code null}
 * @param scoreClamped           true when the two differ, i.e. the model exceeded the rule's weight
 * @param evaluatedTransactionCount how many of the customer's transactions were in the rule's scope
 * @param matchedTransactionIds  the transactions the agent cited as evidence; every one of them was
 *                               checked against the rule's scope before the verdict was accepted
 * @param inScopeTransactionIds  every transaction the rule applies to, i.e. those whose activity
 *                               type matches {@code appliesTo} ({@code ALL} meaning all of them).
 *                               One {@code risk_assessments} row is written per entry, so that "no
 *                               rule was skipped" is provable from the table alone for every rule
 *                               with at least one transaction in scope. Empty when the rule had
 *                               nothing to judge - an {@code ALL}-scoped rule for a customer with no
 *                               activity - in which case the rule writes no rows and the run's
 *                               coverage counters are the record that it was evaluated
 */
public record RuleOutcome(
        UUID ruleId,
        String ruleName,
        RuleScope appliesTo,
        BigDecimal weight,
        boolean triggered,
        BigDecimal score,
        RuleVerdictSource source,
        int evaluatedTransactionCount,
        int matchedCount,
        List<UUID> matchedTransactionIds,
        List<UUID> inScopeTransactionIds,
        String rationale,
        BigDecimal claimedScore,
        boolean scoreClamped) {

    public RuleOutcome {
        matchedTransactionIds = matchedTransactionIds == null ? List.of() : List.copyOf(matchedTransactionIds);
        inScopeTransactionIds = inScopeTransactionIds == null ? List.of() : List.copyOf(inScopeTransactionIds);
    }
}
