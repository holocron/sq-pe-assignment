package com.sq.caa.agent;

/**
 * Raised when a run cannot be reported as a finished analysis, carrying everything the agent did
 * establish before it stopped.
 *
 * <p>Two situations reach here, and they are deliberately the same situation from the caller's point
 * of view - the run is persisted {@code FAILED} with {@link #result()} and this cause:
 *
 * <ul>
 *   <li>the conversation itself broke - the model server refused a request, the connection dropped;
 *   <li>the conversation ended with applicable rules still unjudged
 *       ({@link IncompleteRuleCoverageException}).
 * </ul>
 *
 * <p>In both cases the verdicts the agent already submitted are kept rather than discarded: an
 * analysis that judged nine of twelve rules is not worthless, it is simply not complete, and the
 * three it missed are named in the error and in the trace. What must never happen is the third
 * option - reporting such a run as {@code COMPLETED}.
 */
public class AgentRunFailedException extends RuntimeException {

    private final transient AgentRunResult result;

    public AgentRunFailedException(AgentRunResult result, Throwable cause) {
        super(cause == null ? "The agent run failed" : cause.getMessage(), cause);
        this.result = result;
    }

    /** The run as far as it got, with every verdict the agent submitted. Never {@code null}. */
    public AgentRunResult result() {
        return result;
    }
}
