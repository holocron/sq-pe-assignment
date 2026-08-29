package com.sq.caa.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The agent's judgement of one rule, as submitted through {@code submit_rule_evaluation}.
 *
 * <p>This is the whole verdict: there is no engine behind it to confirm or overturn it. What the
 * tool accepts is therefore what gets scored and what a compliance officer reads, which is why the
 * tool validates hard before a verdict is ever recorded - a rationale is mandatory, matched
 * transaction ids must belong to the rule's own scope, and the score is clamped to the rule's
 * weight.
 *
 * @param score        the score after clamping; this is what the run is scored on
 * @param claimedScore the number the model actually asked for, or {@code null} when it named none.
 *                     Kept so that {@link #scoreClamped()} can say honestly that the model tried to
 *                     award a rule more than its weight
 */
public record AgentRuleVerdict(
        UUID ruleId,
        boolean triggered,
        BigDecimal score,
        BigDecimal claimedScore,
        List<UUID> transactionIds,
        String rationale,
        Instant submittedAt) {

    public AgentRuleVerdict {
        transactionIds = transactionIds == null ? List.of() : List.copyOf(transactionIds);
        // The rationale is rendered verbatim in the coverage table, so it gets the same
        // cleaning as the final narrative - see Narrative.
        rationale = Narrative.clean(rationale);
    }

    /** True when the model asked for a score the rule's weight did not allow. */
    public boolean scoreClamped() {
        return claimedScore != null && score != null && claimedScore.compareTo(score) != 0;
    }
}
