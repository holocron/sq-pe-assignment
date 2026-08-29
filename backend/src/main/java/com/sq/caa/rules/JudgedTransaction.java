package com.sq.caa.rules;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One transaction the model cited as evidence for a rule, resolved back against the run's snapshot.
 *
 * <p>The details are never taken from the model's answer - only the id is. Everything else is read
 * from the {@link EvaluationBatch} the model was shown, so a hallucinated amount cannot reach the
 * screen and an id the model invented is dropped instead of rendered as a blank row.
 *
 * @param reason the model's own note on why this transaction matters, or {@code null} when it gave
 *               none
 */
public record JudgedTransaction(
        UUID transactionId,
        ActivityType activityType,
        BigDecimal amount,
        String currency,
        String status,
        Instant createdAt,
        String reason) {

    /** Quotes a transaction of the batch, with the model's note attached. */
    public static JudgedTransaction of(Transaction transaction, String reason) {
        return new JudgedTransaction(
                transaction.getTransactionId(),
                transaction.getActivityType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getCreatedAt(),
                reason == null || reason.isBlank() ? null : reason.strip());
    }
}
