package com.sq.caa.agent;

import com.sq.caa.domain.RuleScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The settled verdict for one rule of one run: the deterministic result, the agent's claim, and the
 * comparison of the two.
 *
 * <p>One of these exists for every rule of the coverage set on every run - that is the guarantee the
 * coverage gate and the deterministic backfill exist to provide. {@link #score()} is always the
 * deterministic score: on disagreement the engine wins, which is the false-negative safety net the
 * assignment asks for.
 *
 * @param inScopeTransactionIds every transaction the rule was evaluated against; one
 *                              {@code risk_assessments} row is written per entry, so that "no rule
 *                              was skipped" is provable from the table alone for every rule with at
 *                              least one transaction in scope. Empty when the rule had nothing to
 *                              evaluate - an {@code ALL}-scoped rule for a customer with no activity
 *                              - in which case the rule writes no rows and the run's coverage
 *                              counters are the record that it was evaluated
 * @param disagreement          true when the agent's triggered flag contradicted the engine
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
        boolean degraded,
        List<String> degradationNotes,
        String explanation,
        String rationale,
        Boolean agentTriggered,
        BigDecimal agentScore,
        boolean disagreement) {

    public RuleOutcome {
        matchedTransactionIds = matchedTransactionIds == null ? List.of() : List.copyOf(matchedTransactionIds);
        inScopeTransactionIds = inScopeTransactionIds == null ? List.of() : List.copyOf(inScopeTransactionIds);
        degradationNotes = degradationNotes == null ? List.of() : List.copyOf(degradationNotes);
    }

    public boolean fromAgent() {
        return source == RuleVerdictSource.AGENT;
    }
}
