package com.sq.caa.agent;

import com.sq.caa.domain.RiskLevel;
import java.math.BigDecimal;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Everything one agent run produced, ready to be persisted.
 *
 * <p>{@link #totalScore()} is the sum of the mechanical per-rule scores - each one a rule's weight
 * when its SQL matched and zero when it did not - and {@link #mechanicalRiskLevel()} is that total
 * banded. {@link #riskLevel()} is the band actually recorded: the mechanical one, unless the agent
 * escalated above it and said why, in which case {@link #escalationJustification()} carries the
 * reason. The band can never be moved <em>down</em>, so no narrative can talk a scored breach into a
 * clean review. {@link #agentRiskLevel()} is the band the model itself proposed, kept so a reviewer
 * can see where its judgement differed from the arithmetic.
 *
 * <p><b>Coverage is derived, not asserted.</b> {@link #coverageComplete()} is computed from the
 * outcomes actually present, so it cannot be set true by a caller that did not earn it, and
 * {@link #unjudgedRules()} names precisely what is missing when it is false. A run with any unjudged
 * rule is persisted {@code FAILED}; see {@link RiskAgentLoop#execute}.
 */
public record AgentRunResult(
        UUID assessmentId,
        RiskLevel riskLevel,
        RiskLevel mechanicalRiskLevel,
        RiskLevel agentRiskLevel,
        String escalationJustification,
        BigDecimal totalScore,
        String summary,
        String recommendations,
        List<RuleOutcome> ruleOutcomes,
        int rulesTotal,
        List<UnjudgedRule> unjudgedRules,
        int steps,
        String model,
        long durationMs) {

    public AgentRunResult {
        ruleOutcomes = ruleOutcomes == null ? List.of() : List.copyOf(ruleOutcomes);
        unjudgedRules = unjudgedRules == null ? List.of() : List.copyOf(unjudgedRules);
    }

    /** True when the recorded band was raised above the one the rule scores band to. */
    public boolean escalated() {
        return mechanicalRiskLevel != null && riskLevel != null
                && riskLevel.compareTo(mechanicalRiskLevel) > 0;
    }

    /** Rules the agent actually returned a verdict for. */
    public int rulesJudged() {
        return ruleOutcomes.size();
    }

    /** True only when every applicable rule ended with a verdict. */
    public boolean coverageComplete() {
        return unjudgedRules.isEmpty() && ruleOutcomes.size() >= rulesTotal;
    }

    /** The unjudged rules named for an operator, e.g. in {@code analysis_runs.error}. */
    public String unjudgedRuleNames() {
        StringJoiner names = new StringJoiner(", ");
        unjudgedRules.forEach(rule -> names.add("'" + rule.ruleName() + "' (" + rule.ruleId() + ")"));
        return names.toString();
    }
}
