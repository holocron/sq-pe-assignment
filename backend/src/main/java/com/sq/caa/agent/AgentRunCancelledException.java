package com.sq.caa.agent;

/**
 * Raised when a run is aborted at the user's request, carrying everything the agent had established
 * up to that turn. The caller persists the run {@code CANCELLED} with {@link #result()} - a
 * cancelled run is not a failed one: nothing went wrong, and the verdicts already obtained are kept.
 */
public class AgentRunCancelledException extends RuntimeException {

    private final transient AgentRunResult result;

    public AgentRunCancelledException(AgentRunResult result) {
        super("The analysis was cancelled at the user's request.");
        this.result = result;
    }

    /** The run as far as it got, with every verdict the agent submitted. Never {@code null}. */
    public AgentRunResult result() {
        return result;
    }
}
