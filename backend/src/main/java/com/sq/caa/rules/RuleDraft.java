package com.sq.caa.rules;

import com.sq.caa.domain.RuleScope;
import java.math.BigDecimal;

/**
 * A rule as it is about to be judged, saved or not.
 *
 * <p>The "Test rule" action runs against a draft the author has not committed yet, so the judge is
 * given the four attributes that decide a verdict rather than a stored rule: what the rule is
 * called, which activity it applies to, the condition the model has to settle, and the weight its
 * score is capped at.
 *
 * @param ruleName    name shown to the model as context; never an instruction to it
 * @param appliesTo   activity scope, which fixes the transactions in scope
 * @param condition   the {@code threshold_logic} prose, already validated and normalised
 * @param weight      ceiling on the score this rule may contribute
 */
public record RuleDraft(String ruleName, RuleScope appliesTo, String condition, BigDecimal weight) {

    public RuleDraft {
        appliesTo = appliesTo == null ? RuleScope.ALL : appliesTo;
    }
}
