package com.sq.caa.agent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The transcripts of the analyses that are running right now.
 *
 * <p>A run's {@link AnalysisTrace} lives here from the moment the analysis is accepted until it has
 * been persisted, which is what lets {@code GET /api/analyses/{id}/stream} attach to a run that is
 * already in flight and replay everything it has missed. Once the run is written to the database the
 * trace is closed and dropped, and later readers are served from {@code analysis_runs.trace}.
 *
 * <p>This class also owns the fan-out threads. SSE writes are blocking servlet IO, and the thread
 * that records a step is the thread running the analysis: doing the writing there would let a
 * suspended browser tab hold up a compliance analysis. Every transcript created here therefore
 * hands its socket writes to this small dedicated pool instead.
 */
@Component
public class AnalysisStreamRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnalysisStreamRegistry.class);

    /**
     * Threads doing the SSE writing. Two is plenty: a subscriber that blocks holds one thread only
     * until its own socket write times out, and the queues in front of them absorb the burst.
     */
    private static final int FAN_OUT_THREADS = 2;

    private final Map<UUID, AnalysisTrace> running = new ConcurrentHashMap<>();
    private final ExecutorService fanOut =
            Executors.newFixedThreadPool(FAN_OUT_THREADS, new StreamThreadFactory());

    /** Starts tracking a run and returns the transcript the agent must write into. */
    public AnalysisTrace open(UUID assessmentId) {
        AnalysisTrace trace = new AnalysisTrace(assessmentId, JsonNodeFactory.instance, fanOut);
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

    @Override
    public void close() {
        fanOut.shutdown();
        try {
            if (!fanOut.awaitTermination(2, TimeUnit.SECONDS)) {
                fanOut.shutdownNow();
            }
        } catch (InterruptedException e) {
            fanOut.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.debug("Analysis stream fan-out stopped");
    }

    private static final class StreamThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "analysis-sse-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
