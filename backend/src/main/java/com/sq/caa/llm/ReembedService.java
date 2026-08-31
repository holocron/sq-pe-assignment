package com.sq.caa.llm;

import com.sq.caa.domain.KnowledgeDocument;
import com.sq.caa.rag.RagService;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Re-embeds the whole knowledge base after the embedding model changed.
 *
 * <p>Runs on a single dedicated daemon thread - deliberately not a shared executor bean, matching
 * {@code AnalysisExecutor}'s reasoning: a bounded, owned pool that cannot starve MVC's async
 * requests. Only one run can be in flight; a second {@link #start()} while one is running is a
 * no-op that reports {@code false}.
 *
 * <p>Every document goes back through the real ingest path ({@link RagService#reindex}): the
 * stored original bytes are re-extracted, re-chunked and re-embedded with the <em>new</em> model.
 * A document uploaded before the source bytes were retained (pre-V7) has nothing to re-extract
 * from; it is counted failed with an explicit reason rather than silently re-chunked from
 * concatenated chunk text. One document's failure never aborts the run.
 */
@Service
public class ReembedService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReembedService.class);

    private final RagService ragService;
    private final ExecutorService executor;

    private final Object lock = new Object();
    private boolean running;
    private int totalDocuments;
    private final AtomicInteger completedDocuments = new AtomicInteger();
    private final AtomicInteger failedDocuments = new AtomicInteger();
    private volatile String lastError;

    public ReembedService(RagService ragService) {
        this.ragService = ragService;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "reembed-job");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts a re-embed run if none is in flight.
     *
     * @return {@code true} when this call started the job, {@code false} when one is already running
     */
    public boolean start() {
        synchronized (lock) {
            if (running) {
                return false;
            }
            running = true;
            completedDocuments.set(0);
            failedDocuments.set(0);
            lastError = null;
        }
        executor.submit(this::run);
        return true;
    }

    /** The current snapshot; safe to call from any thread. */
    public ReembedStatus status() {
        synchronized (lock) {
            return new ReembedStatus(running, totalDocuments, completedDocuments.get(),
                    failedDocuments.get(), lastError);
        }
    }

    private void run() {
        try {
            List<KnowledgeDocument> documents = ragService.listDocuments();
            synchronized (lock) {
                totalDocuments = documents.size();
            }
            log.info("Re-embed job starting over {} document(s)", documents.size());
            for (KnowledgeDocument document : documents) {
                try {
                    ragService.reindex(document.getDocumentId());
                    completedDocuments.incrementAndGet();
                    log.info("Re-embedded '{}' ({})", document.getFilename(), document.getDocumentId());
                } catch (RuntimeException e) {
                    failedDocuments.incrementAndGet();
                    String reason = document.getFilename() + ": " + rootMessage(e);
                    lastError = reason;
                    log.warn("Re-embedding of '{}' failed: {}", document.getFilename(), reason);
                }
            }
            log.info("Re-embed job finished: {} completed, {} failed of {}",
                    completedDocuments.get(), failedDocuments.get(), totalDocuments);
        } catch (RuntimeException e) {
            // The job itself broke (e.g. the corpus listing failed) - still never propagate.
            lastError = rootMessage(e);
            log.error("Re-embed job aborted", e);
        } finally {
            synchronized (lock) {
                running = false;
            }
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
