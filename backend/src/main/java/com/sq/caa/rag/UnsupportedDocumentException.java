package com.sq.caa.rag;

/**
 * The uploaded bytes are not a {@code .docx} or a {@code .pdf}.
 *
 * <p>Surfaced by {@code KnowledgeController} as {@code 400 application/problem+json}. The
 * {@link #detected()} value names what the content actually looked like, which is far more useful
 * to an administrator than "unsupported file type".
 */
public class UnsupportedDocumentException extends RuntimeException {

    private final String filename;
    private final String detected;

    public UnsupportedDocumentException(String filename, String detected, String message) {
        super(message);
        this.filename = filename;
        this.detected = detected;
    }

    /** Original file name as sent by the browser, may be null. */
    public String filename() {
        return filename;
    }

    /** Short label of what the bytes actually were, for example {@code "ZIP archive (not a .docx)"}. */
    public String detected() {
        return detected;
    }
}
