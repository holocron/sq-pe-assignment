package com.sq.caa.rag;

/**
 * The file is a real {@code .docx} or {@code .pdf} but its text could not be read - it is corrupt,
 * encrypted, extraction-restricted or contains no extractable text at all (a scan, for instance).
 *
 * <p>Surfaced as {@code 400 application/problem+json}: the request is at fault, not the server.
 */
public class DocumentExtractionException extends RuntimeException {

    private final String filename;

    public DocumentExtractionException(String filename, String message) {
        super(message);
        this.filename = filename;
    }

    public DocumentExtractionException(String filename, String message, Throwable cause) {
        super(message, cause);
        this.filename = filename;
    }

    public String filename() {
        return filename;
    }
}
