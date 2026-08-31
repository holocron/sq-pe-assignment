package com.sq.caa.llm;

/**
 * Applying new LLM settings failed on our side - e.g. the {@code ALTER TABLE} that changes
 * {@code document_chunks.embedding} to the new dimension was refused by the database (the
 * application role needs to own the table).
 */
public class LlmSettingsException extends RuntimeException {

    public LlmSettingsException(String message, Throwable cause) {
        super(message, cause);
    }
}
