package com.sq.caa.agent;

/**
 * Where a rule's verdict in an analysis run came from.
 *
 * <p>Persisted in the run's trace and surfaced on the analysis page so a reviewer can see, per rule,
 * whether the agent actually reasoned about it or whether the coverage backfill had to complete it.
 */
public enum RuleVerdictSource {

    /** The agent called {@code submit_rule_evaluation} for this rule. */
    AGENT,

    /**
     * The agent never ruled on this rule, so the deterministic engine did after the loop ended. This
     * is the mechanism that makes rule coverage 100% on every run.
     */
    DETERMINISTIC_FALLBACK
}
