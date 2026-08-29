package com.sq.caa.rules;

import com.sq.caa.domain.ActivityType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One transaction that satisfied a rule, with the trace that shows why.
 *
 * @param explanation the rule rendered against this transaction's values, so an operator can check
 *                    the verdict without re-running anything
 */
public record RuleMatch(
        UUID transactionId,
        UUID customerId,
        String customerName,
        ActivityType activityType,
        BigDecimal amount,
        String currency,
        String status,
        Instant createdAt,
        String explanation) {
}
