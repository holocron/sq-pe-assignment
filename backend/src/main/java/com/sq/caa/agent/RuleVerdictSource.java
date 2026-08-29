package com.sq.caa.agent;

/**
 * Where a rule's verdict in an analysis run came from.
 *
 * <p>There is exactly one origin now. {@code risk_rules.threshold_logic} holds the rule condition in
 * prose, the agent reads it, gathers the evidence with its data tools and judges it; nothing else in
 * the system produces a verdict, and nothing overrules the one the agent submitted. The enum is kept
 * because the value is persisted in {@code analysis_runs.trace} and rendered per rule on the
 * analysis page: a reviewer reading a stored run must be able to see, without inference, that what
 * they are looking at is a model's judgement rather than a computation.
 *
 * <p>A rule the agent never judged has no verdict at all - it is not represented by another enum
 * value, it is the reason the run is recorded as {@code FAILED}. See {@link RiskAgentLoop}.
 */
public enum RuleVerdictSource {

    /** The agent read the rule condition, weighed the evidence and called {@code submit_rule_evaluation}. */
    AGENT_JUDGED
}
