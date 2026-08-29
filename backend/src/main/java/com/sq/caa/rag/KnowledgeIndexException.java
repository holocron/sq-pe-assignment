package com.sq.caa.rag;

/**
 * Embedding or vector-store access failed - the embedding model is unreachable, returned the wrong
 * dimensionality, or PostgreSQL rejected the write.
 *
 * <p>This is a server-side fault, so it is surfaced as {@code 503 Service Unavailable}: the upload
 * or the search can be retried once the model router is back.
 */
public class KnowledgeIndexException extends RuntimeException {

    public KnowledgeIndexException(String message) {
        super(message);
    }

    public KnowledgeIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
