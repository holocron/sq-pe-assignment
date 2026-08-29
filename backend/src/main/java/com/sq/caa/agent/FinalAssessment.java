package com.sq.caa.agent;

import com.sq.caa.domain.RiskLevel;

/**
 * The narrative conclusion the agent submitted through {@code submit_final_assessment}.
 *
 * <p>{@code riskLevel} is the agent's overall judgement, formed before it adds up anything. It is
 * recorded and shown, but the persisted {@code analysis_runs.risk_level} is banded from the total of
 * the per-rule scores the agent submitted, so the number and the band can never contradict each
 * other.
 *
 * <p>Both narratives pass through {@link Narrative#clean} here rather than at the call sites, so the
 * tool path and the prose-parser path cannot diverge. A narrative that carries nothing readable
 * becomes null, which is the signal the loop uses to generate a summary from the verdicts instead.
 */
public record FinalAssessment(RiskLevel riskLevel, String summary, String recommendations) {

    public FinalAssessment {
        summary = Narrative.clean(summary);
        recommendations = Narrative.clean(recommendations);
    }
}
