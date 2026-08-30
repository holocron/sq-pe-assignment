package com.sq.caa.agent;

import com.sq.caa.domain.RuleScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One rule of one run as PostgreSQL settled it: the verdict, the query that produced it, the
 * evidence it returned and the agent's account of what the query was looking for.
 *
 * <p>An outcome exists only for a rule whose query actually executed. That is deliberate: a rule
 * with no outcome was not evaluated, and a run in that state cannot be reported as complete - it is
 * persisted {@code FAILED} with the unjudged rules named. Writing a placeholder outcome would put a
 * "not triggered, 0.00" row into {@code risk_assessments} for a rule whose condition was never
 * answered, which is exactly the false assurance the coverage guarantee exists to prevent.
 *
 * @param triggered              {@code true} exactly when the rule's query returned at least one row
 * @param score                  the rule's {@link #weight()} when triggered, {@code 0.00} otherwise;
 *                               mechanical, never an estimate
 * @param evaluatedTransactionCount how many of the customer's transactions were in the rule's scope
 * @param matchedCount           how many rows the query returned in total, even when
 *                               {@link #matchedTransactionIds()} was capped by the evaluator
 * @param matchedTransactionIds  the transactions the query returned; every one of them was checked
 *                               against the rule's scope before the verdict was accepted
 * @param inScopeTransactionIds  every transaction the rule applies to, i.e. those whose activity
 *                               type matches {@code appliesTo} ({@code ALL} meaning all of them).
 *                               One {@code risk_assessments} row is written per entry, so that "no
 *                               rule was skipped" is provable from the table alone for every rule
 *                               with at least one transaction in scope. Empty when the rule had
 *                               nothing to judge - an {@code ALL}-scoped rule for a customer with no
 *                               activity - in which case the rule writes no rows and the run's
 *                               coverage counters are the record that it was evaluated
 * @param rationale              the agent's one-line account of what its query looked for
 * @param sql                    the full SQL that was executed, so a reviewer can see exactly what
 *                               was computed rather than take the verdict on trust
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
        String sql) {

    public RuleOutcome {
        matchedTransactionIds = matchedTransactionIds == null ? List.of() : List.copyOf(matchedTransactionIds);
        inScopeTransactionIds = inScopeTransactionIds == null ? List.of() : List.copyOf(inScopeTransactionIds);
    }
}
