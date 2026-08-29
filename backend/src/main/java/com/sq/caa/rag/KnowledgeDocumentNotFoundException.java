package com.sq.caa.rag;

import java.util.UUID;

/** No {@code knowledge_documents} row with the requested id. Surfaced as {@code 404}. */
public class KnowledgeDocumentNotFoundException extends RuntimeException {

    private final UUID documentId;

    public KnowledgeDocumentNotFoundException(UUID documentId) {
        super("No knowledge document with id " + documentId + ".");
        this.documentId = documentId;
    }

    public UUID documentId() {
        return documentId;
    }
}
