package com.sq.caa.agent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The bounded pool analyses run on.
 *
 * <p>One run is minutes of model time against a single local inference server, so concurrency is
 * capped and the backlog is finite: past the queue the caller is told {@code 503} rather than being
 * put behind work it will never see finish.
 *
 * <p>Deliberately <em>not</em> an {@link java.util.concurrent.Executor} bean. Spring Boot's
 * {@code applicationTaskExecutor} auto-configuration backs off as soon as any {@code Executor} bean
 * exists, and that executor is what Spring MVC uses for asynchronous requests - including the SSE
 * endpoint this very feature depends on. Wrapping the pool keeps both.
 */
@Component
public class AnalysisExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnalysisExecutor.class);

    private final ThreadPoolExecutor pool;

    public AnalysisExecutor(AgentProperties properties) {
        this.pool = new ThreadPoolExecutor(
                properties.concurrentRuns(),
                properties.concurrentRuns(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                new AnalysisThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Queues one analysis.
     *
     * @throws RejectedExecutionException when the pool and its queue are both full
     */
    public void submit(Runnable task) {
        pool.execute(task);
    }

    /** Analyses currently executing. */
    public int running() {
        return pool.getActiveCount();
    }

    /** Analyses waiting to start. */
    public int queued() {
        return pool.getQueue().size();
    }

    @Override
    public void close() {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Analysis executor did not stop within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class AnalysisThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "risk-agent-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
