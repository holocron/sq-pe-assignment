package com.sq.caa.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the agent claimed about one rule through {@code submit_rule_evaluation}.
 *
 * <p>Deliberately kept separate from the deterministic verdict: the two are compared afterwards and
 * the difference is what the trace reports. The agent's {@code score} is advisory - scoring always
 * uses the deterministic engine - but it is retained so a reviewer can see what the model believed.
 */
public record AgentRuleVerdict(
        UUID ruleId,
        boolean triggered,
        BigDecimal score,
        List<UUID> transactionIds,
        String rationale,
        Instant submittedAt) {

    public AgentRuleVerdict {
        transactionIds = transactionIds == null ? List.of() : List.copyOf(transactionIds);
    }
}
