package com.sq.caa.rag;

import java.util.UUID;

/**
 * A document with the same file name is already indexed.
 *
 * <p>Re-indexing the same file silently would leave the operator unable to tell which revision the
 * agent is citing, so the upload is refused with {@code 409} and the caller is told which document
 * to delete first.
 */
public class DuplicateDocumentException extends RuntimeException {

    private final String filename;
    private final UUID existingDocumentId;

    public DuplicateDocumentException(String filename, UUID existingDocumentId) {
        super("A document named '" + filename + "' is already indexed. Delete it before uploading a "
                + "new revision.");
        this.filename = filename;
        this.existingDocumentId = existingDocumentId;
    }

    public String filename() {
        return filename;
    }

    public UUID existingDocumentId() {
        return existingDocumentId;
    }
}
