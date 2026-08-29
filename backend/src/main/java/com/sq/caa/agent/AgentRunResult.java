package com.sq.caa.agent;

import com.sq.caa.domain.RiskLevel;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Everything one completed agent run produced, ready to be persisted.
 *
 * <p>{@link #riskLevel()} is banded from {@link #totalScore()}, which is the sum of the
 * <em>deterministic</em> rule scores. {@link #agentRiskLevel()} is what the model proposed. Keeping
 * both is the point: the score and the band can never contradict each other, and a reviewer can
 * still see where the model's judgement differed from the arithmetic.
 *
 * @param rulesEvaluatedByAgent how many rules the agent itself ruled on; the remainder were
 *                              completed by the deterministic backfill, so coverage is always
 *                              {@code ruleOutcomes.size() == rulesTotal}
 * @param coverageComplete      whether the agent needed no backfill at all
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
        int rulesEvaluatedByAgent,
        boolean coverageComplete,
        int disagreementCount,
        int steps,
        String model,
        long durationMs) {

    public AgentRunResult {
        ruleOutcomes = ruleOutcomes == null ? List.of() : List.copyOf(ruleOutcomes);
    }

    /** Rules whose verdict came from the deterministic backfill rather than from the agent. */
    public int rulesBackfilled() {
        return rulesTotal - rulesEvaluatedByAgent;
    }
}
