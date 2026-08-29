package com.sq.caa.agent;

import com.sq.caa.domain.RiskLevel;
import java.math.BigDecimal;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Everything one agent run produced, ready to be persisted.
 *
 * <p>{@link #totalScore()} is the sum of the scores the agent estimated for the rules it judged,
 * each already clamped to its rule's weight, and {@link #riskLevel()} is that total banded.
 * {@link #agentRiskLevel()} is the band the model itself proposed. Both are kept: the score and the
 * band can then never contradict each other, while a reviewer can still see where the model's
 * overall judgement differed from the arithmetic of its own rule scores.
 *
 * <p><b>Coverage is derived, not asserted.</b> {@link #coverageComplete()} is computed from the
 * outcomes actually present, so it cannot be set true by a caller that did not earn it, and
 * {@link #unjudgedRules()} names precisely what is missing when it is false. A run with any unjudged
 * rule is persisted {@code FAILED}; see {@link RiskAgentLoop#execute}.
 */
public record AgentRunResult(
        UUID assessmentId,
        RiskLevel riskLevel,
        RiskLevel agentRiskLevel,
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

    /** Rules the agent actually returned a verdict for. */
    public int rulesJudged() {
        return ruleOutcomes.size();
    }

    /** True only when every applicable rule ended with an agent verdict. */
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
