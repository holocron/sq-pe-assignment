package com.sq.caa.llm;

/**
 * The configured (or candidate) LLM endpoint could not be reached or answered badly - surfaced as
 * {@code 502} because the failure belongs to the upstream server, not to this one.
 */
public class LlmEndpointException extends RuntimeException {

    public LlmEndpointException(String message) {
        super(message);
    }

    public LlmEndpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
