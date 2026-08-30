package com.sq.caa.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One rule's verdict, as PostgreSQL answered it.
 *
 * <p>Nothing here is the model's opinion. The agent wrote a SELECT expressing the rule's condition
 * and {@code evaluate_rule} ran it; {@link #triggered()} is {@code matchedCount > 0},
 * {@link #score()} is the rule's weight or zero, and {@link #transactionIds()} are the ids the query
 * returned. The one model-authored field is {@link #explanation()} - what the query was looking for
 * - which is what a compliance officer reads as the reason. It cannot contradict the verdict,
 * because the verdict was not taken from it.
 *
 * @param score          the rule's weight when the query matched, {@code 0.00} when it did not
 * @param matchedCount   how many rows the query returned in total, even when the id list was capped
 * @param transactionIds the matched transaction ids, possibly capped by the evaluator
 * @param sql            the full SQL that was actually executed, kept for the audit trail
 * @param queryMs        how long PostgreSQL took
 */
public record AgentRuleVerdict(
        UUID ruleId,
        boolean triggered,
        BigDecimal score,
        int matchedCount,
        List<UUID> transactionIds,
        String explanation,
        String sql,
        long queryMs,
        Instant submittedAt) {

    public AgentRuleVerdict {
        transactionIds = transactionIds == null ? List.of() : List.copyOf(transactionIds);
        // The explanation is rendered verbatim in the coverage table, so it gets the same cleaning
        // as the final narrative - see Narrative.
        explanation = Narrative.clean(explanation);
    }

    /** True when the evaluator returned fewer ids than it matched, i.e. the list was truncated. */
    public boolean matchedIdsCapped() {
        return matchedCount > transactionIds.size();
    }
}
