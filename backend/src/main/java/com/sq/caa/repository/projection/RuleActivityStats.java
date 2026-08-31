package com.sq.caa.repository.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-rule activity rollup over {@code risk_assessments}.
 *
 * @param ruleId       the rule the row aggregates
 * @param lastJudgedAt latest {@code triggered_at} recorded for the rule, whatever the score
 * @param lastFiredAt  latest {@code triggered_at} with a positive {@code score_contribution} -
 *                     {@code null} when the rule was judged but never fired
 */
public record RuleActivityStats(UUID ruleId, Instant lastJudgedAt, Instant lastFiredAt) {
}
