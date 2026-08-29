package com.sq.caa.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The stream is a view of the analysis, never a brake on it.
 *
 * <p>SSE writes are blocking servlet IO. Recording a trace step happens on the thread that is
 * running the analysis, so writing to the sockets there means a browser that has stopped reading -
 * a suspended tab, a paused proxy, a full TCP receive window - holds up a compliance analysis until
 * the write times out. With only {@code caa.agent.concurrent-runs} worker threads (2 by default),
 * two such clients can stall the queue.
 *
 * <p>Both tests here hang, and therefore fail on their timeout, if the fan-out ever goes back to
 * writing on the calling thread.
 */
class AnalysisTraceStreamTest {

    private final ExecutorService fanOut = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopFanOut() {
        fanOut.shutdownNow();
    }

    @Test
    @DisplayName("a subscriber that has stopped reading does not slow the run down")
    void aStalledSubscriberDoesNotBlockTheAnalysisThread() throws Exception {
        BlockingEmitter stalled = new BlockingEmitter();
        AnalysisTrace trace = new AnalysisTrace(UUID.randomUUID(), JsonNodeFactory.instance, fanOut);
        assertTrue(trace.subscribe(stalled, "{\"status\":\"RUNNING\"}"));
        assertTrue(stalled.blockedOnFirstSend.await(5, TimeUnit.SECONDS),
                "the fan-out must have started writing on its own thread");

        // The analysis keeps working while that client is wedged inside a socket write.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int step = 0; step < 200; step++) {
                trace.assistant("step " + step);
            }
        }, "recording a trace step must never wait for an SSE client");

        assertEquals(200, trace.size());

        // ... and once the client starts reading again it still receives everything, in order.
        stalled.release();
        waitUntil(() -> stalled.events.size() >= 201);
        assertEquals(201, stalled.events.size(), "one status event plus every step");
        assertTrue(stalled.events.get(1).contains("step 0"));
        assertTrue(stalled.events.get(200).contains("step 199"));

        trace.close();
        waitUntil(stalled.completed::get);
        assertEquals(0, trace.subscriberCount());
    }

    @Test
    @DisplayName("a subscriber that falls too far behind is dropped, not allowed to grow without bound")
    void aHopelesslySlowSubscriberIsDropped() throws Exception {
        BlockingEmitter stalled = new BlockingEmitter();
        AnalysisTrace trace = new AnalysisTrace(UUID.randomUUID(), JsonNodeFactory.instance, fanOut);
        trace.subscribe(stalled, "{\"status\":\"RUNNING\"}");
        assertTrue(stalled.blockedOnFirstSend.await(5, TimeUnit.SECONDS));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int step = 0; step < 2000; step++) {
                trace.assistant("step " + step);
            }
        });
        assertEquals(2000, trace.size(), "the transcript itself is never truncated");

        stalled.release();
        waitUntil(stalled.completed::get);
        assertTrue(stalled.events.size() < 2001,
                "the backlog of a dead client must be bounded, not the whole run");
        assertEquals(0, trace.subscriberCount(), "the dropped client must be unsubscribed");

        // The run is entirely unaffected: recording still works after the drop.
        trace.assistant("after the drop");
        assertEquals(2001, trace.size());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition was never met within 5s");
    }

    /** An SSE client that stops reading: the first write blocks until the test lets it go. */
    private static final class BlockingEmitter extends SseEmitter {

        private final List<String> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch blockedOnFirstSend = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile boolean blockedOnce;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (!blockedOnce) {
                blockedOnce = true;
                blockedOnFirstSend.countDown();
                try {
                    if (!released.await(30, TimeUnit.SECONDS)) {
                        throw new IOException("the test never released the client");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            StringBuilder rendered = new StringBuilder();
            for (DataWithMediaType part : builder.build()) {
                rendered.append(part.getData());
            }
            events.add(rendered.toString());
        }

        @Override
        public void complete() {
            completed.set(true);
        }

        private void release() {
            released.countDown();
        }
    }
}
