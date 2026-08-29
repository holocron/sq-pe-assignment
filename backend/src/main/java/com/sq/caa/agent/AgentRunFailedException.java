package com.sq.caa.agent;

/**
 * Raised when the ReAct conversation could not be finished, carrying the run settled from whatever
 * the agent had already established.
 *
 * <p>An agent run is a long chain of work: by the time the model server refuses a request or the
 * network drops, the agent has usually already submitted verdicts for most of the coverage set.
 * Discarding those and re-deriving everything from the rule engine would throw away real analysis
 * and mislabel it as a deterministic fallback. So {@link RiskAgentLoop#execute} settles the run it
 * has - agent verdicts kept, the remaining rules backfilled by the engine, coverage still 100% - and
 * hands the settled result over inside this exception. The caller persists {@link #result()} and
 * marks the run {@code FAILED} with this cause.
 */
public class AgentRunFailedException extends RuntimeException {

    private final transient AgentRunResult result;

    public AgentRunFailedException(AgentRunResult result, Throwable cause) {
        super(cause == null ? "The agent run failed" : cause.getMessage(), cause);
        this.result = result;
    }

    /** The run as far as it got, with the coverage set closed. Never {@code null}. */
    public AgentRunResult result() {
        return result;
    }
}
