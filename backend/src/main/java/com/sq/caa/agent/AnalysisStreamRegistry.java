package com.sq.caa.agent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The transcripts of the analyses that are running right now.
 *
 * <p>A run's {@link AnalysisTrace} lives here from the moment the analysis is accepted until it has
 * been persisted, which is what lets {@code GET /api/analyses/{id}/stream} attach to a run that is
 * already in flight and replay everything it has missed. Once the run is written to the database the
 * trace is closed and dropped, and later readers are served from {@code analysis_runs.trace}.
 */
@Component
public class AnalysisStreamRegistry {

    private final Map<UUID, AnalysisTrace> running = new ConcurrentHashMap<>();

    /** Starts tracking a run and returns the transcript the agent must write into. */
    public AnalysisTrace open(UUID assessmentId) {
        AnalysisTrace trace = new AnalysisTrace(assessmentId, JsonNodeFactory.instance);
        running.put(assessmentId, trace);
        return trace;
    }

    /** The live transcript of a run, if it is still in flight. */
    public Optional<AnalysisTrace> find(UUID assessmentId) {
        return Optional.ofNullable(running.get(assessmentId));
    }

    public boolean isRunning(UUID assessmentId) {
        return running.containsKey(assessmentId);
    }

    /** Completes every subscriber of a run and stops tracking it. */
    public void close(UUID assessmentId) {
        AnalysisTrace trace = running.remove(assessmentId);
        if (trace != null) {
            trace.close();
        }
    }

    /** How many analyses are currently in flight; used by the readiness of the queue check. */
    public int activeCount() {
        return running.size();
    }
}
