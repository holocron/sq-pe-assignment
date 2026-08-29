package com.sq.caa.repository.projection;

import com.sq.caa.domain.RuleScope;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One rule of an analysis run rolled up over the transactions it was evaluated against. Backs the
 * per-rule coverage table on the analysis page.
 */
public interface RuleEvaluationRow {

    UUID getRuleId();

    String getRuleName();

    RuleScope getAppliesTo();

    BigDecimal getWeight();

    /** How many transactions this rule was evaluated against. */
    long getEvaluatedCount();

    /** How many of those transactions the rule fired on. */
    long getTriggeredCount();

    /** Sum of the score contributions of this rule within the run. */
    BigDecimal getScore();
}
