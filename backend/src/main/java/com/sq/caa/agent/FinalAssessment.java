package com.sq.caa.agent;

import com.sq.caa.domain.RiskLevel;

/**
 * The narrative conclusion the agent submitted through {@code submit_final_assessment}.
 *
 * <p>{@code riskLevel} is the agent's own judgement. It is recorded and shown, but the persisted
 * {@code analysis_runs.risk_level} is banded from the deterministic total score, so the number and
 * the band can never contradict each other.
 */
public record FinalAssessment(RiskLevel riskLevel, String summary, String recommendations) {
}
