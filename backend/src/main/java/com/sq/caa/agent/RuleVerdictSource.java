package com.sq.caa.agent;

/**
 * Where a rule's verdict in an analysis run came from.
 *
 * <p>There is exactly one origin. {@code risk_rules.threshold_logic} holds the rule condition in
 * prose; the agent translates that condition into a SELECT, PostgreSQL executes it, and the verdict
 * is read off the result mechanically - triggered when the query returned rows, not triggered when
 * it returned none. The model chooses the query; it never performs the count, the comparison or the
 * scoring. The enum is kept because the value is persisted in {@code analysis_runs.trace} and
 * rendered per rule on the analysis page: a reviewer reading a stored run must be able to see,
 * without inference, that the verdict was computed by the database rather than estimated by a
 * language model.
 *
 * <p>A rule whose query never executed successfully has no verdict at all - it is not represented by
 * another enum value, it is the reason the run is recorded as {@code FAILED}. See
 * {@link RiskAgentLoop}.
 */
public enum RuleVerdictSource {

    /** The agent wrote the SQL, PostgreSQL answered it, and the answer is the verdict. */
    SQL_DERIVED
}
