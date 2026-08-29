package com.sq.caa.rules;

import com.sq.caa.domain.RuleScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * The model's verdict on one rule for one customer.
 *
 * <p>This is what {@code POST /api/rules/test} answers with. It is a judgement, not a computation:
 * the same draft judged twice may come back with a different score or a different set of cited
 * transactions, and the response says so rather than pretending otherwise - {@link #model()} and
 * {@link #durationMs()} are part of the payload precisely so a reviewer can see it was a model call.
 *
 * @param triggered                 the model's verdict
 * @param score                     its estimated contribution, always {@code 0.00} when not
 *                                  triggered and never above {@link #weight()}
 * @param weight                    the rule's weight, echoed so the score can be read in proportion
 * @param matchedTransactions       the transactions it cited, resolved against the batch; ids it
 *                                  invented are dropped before they get here
 * @param evaluatedTransactionCount transactions that were in scope and shown to it
 * @param rationale                 its reasoning, verbatim
 * @param notes                     anything the caller should know about how the verdict was
 *                                  reached: evidence that had to be truncated, cited ids that did
 *                                  not exist, a score that had to be capped
 */
public record RuleJudgement(
        String ruleName,
        RuleScope appliesTo,
        BigDecimal weight,
        UUID customerId,
        String customerName,
        boolean triggered,
        BigDecimal score,
        List<JudgedTransaction> matchedTransactions,
        int matchedCount,
        int evaluatedTransactionCount,
        String rationale,
        String model,
        long durationMs,
        List<String> notes) {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    public RuleJudgement {
        matchedTransactions = matchedTransactions == null ? List.of() : List.copyOf(matchedTransactions);
        notes = notes == null ? List.of() : List.copyOf(notes);
        weight = weight == null ? ZERO : weight.setScale(2, RoundingMode.HALF_UP);
        score = score == null ? ZERO : score.setScale(2, RoundingMode.HALF_UP);
    }
}
