package com.sq.caa.rules;

import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * The verdict of the deterministic engine for one rule over one customer.
 *
 * <p>This is the object the agent tool {@code evaluate_rule_deterministically} returns, the object
 * the coverage backfill persists, and the object the analysis page renders, so its shape is a
 * contract. It is serialised as-is by Jackson (camelCase field names).
 *
 * @param ruleId                   rule that was evaluated
 * @param ruleName                 rule name, echoed so the agent never has to look it up again
 * @param appliesTo                scope of the rule, which decides the transactions considered
 * @param weight                   configured weight of the rule
 * @param triggered                true when at least one in-scope transaction matched
 * @param score                    {@code weight} when triggered, otherwise {@code 0.00} - the score
 *                                 is capped at the weight no matter how many transactions matched
 * @param matchedTransactionIds    every matching transaction, newest first
 * @param matchedCount             size of {@code matchedTransactionIds}
 * @param evaluatedTransactionCount transactions actually tested (after scope filtering)
 * @param degraded                 true when some condition could not be evaluated as written
 * @param degradationNotes         why it was degraded, de-duplicated
 * @param explanation              human-readable justification, quoted in the UI and by the agent
 * @param sampleMatches            up to five matches with their per-transaction traces
 */
public record RuleEvaluationResult(
        UUID ruleId,
        String ruleName,
        RuleScope appliesTo,
        BigDecimal weight,
        boolean triggered,
        BigDecimal score,
        List<UUID> matchedTransactionIds,
        int matchedCount,
        int evaluatedTransactionCount,
        boolean degraded,
        List<String> degradationNotes,
        String explanation,
        List<RuleMatch> sampleMatches) {

    /** How many matches carry a full trace in {@link #sampleMatches()}. */
    public static final int SAMPLE_LIMIT = 5;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    public RuleEvaluationResult {
        matchedTransactionIds = matchedTransactionIds == null ? List.of() : List.copyOf(matchedTransactionIds);
        degradationNotes = degradationNotes == null ? List.of() : List.copyOf(degradationNotes);
        sampleMatches = sampleMatches == null ? List.of() : List.copyOf(sampleMatches);
        weight = weight == null ? ZERO : weight;
        score = score == null ? ZERO : score;
    }

    /** Score of a rule that could not even be parsed, or that did not trigger. */
    public static BigDecimal zeroScore() {
        return ZERO;
    }

    /** Result for a rule whose {@code threshold_logic} could not be parsed. Never triggers. */
    public static RuleEvaluationResult unparseable(RiskRule rule, String reason) {
        return new RuleEvaluationResult(
                rule.getRuleId(),
                rule.getRuleName(),
                rule.getAppliesTo(),
                scale(rule.getWeight()),
                false,
                ZERO,
                List.of(),
                0,
                0,
                true,
                List.of(reason),
                "Rule '" + rule.getRuleName() + "' could not be evaluated because its logic is invalid: "
                        + reason + ". It is reported as not triggered and flagged degraded so the "
                        + "defect is visible instead of silently scoring zero.",
                List.of());
    }

    static BigDecimal scale(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
