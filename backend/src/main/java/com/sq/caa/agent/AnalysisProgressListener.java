package com.sq.caa.agent;

/**
 * Notified as a run advances, so a RUNNING analysis can report real progress before it ends.
 *
 * <p>Without this the whole multi-minute run is invisible to anything but the SSE transcript:
 * {@code analysis_runs.steps} and {@code rules_evaluated} stay at 0 until the final persist, so
 * {@code GET /api/analyses/{id}} - the client's polling fallback whenever the stream drops - shows
 * "0/12, 0 steps" for the entire run and looks frozen.
 *
 * <p>Implementations are called from the analysis worker thread on every model turn and every rule
 * verdict. They must be cheap and must not throw; {@link AgentRunContext} isolates the run from
 * both, but the point of the contract is that reporting progress can never slow the analysis down.
 *
 * @see com.sq.caa.service.RiskAnalysisService
 */
@FunctionalInterface
public interface AnalysisProgressListener {

    /** Ignores progress; used by the deterministic-only path and by tests. */
    AnalysisProgressListener NONE = (steps, rulesEvaluated, rulesTotal) -> {
    };

    /**
     * @param steps          model turns taken so far
     * @param rulesEvaluated rules of the coverage set that already have an agent verdict
     * @param rulesTotal     size of the coverage set
     */
    void onProgress(int steps, int rulesEvaluated, int rulesTotal);
}
