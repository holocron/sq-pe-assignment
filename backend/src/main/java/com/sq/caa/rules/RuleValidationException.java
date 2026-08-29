package com.sq.caa.rules;

/**
 * Thrown when a {@code threshold_logic} document is malformed or references something the catalog
 * does not know.
 *
 * <p>It always names the offending node: {@link #path()} is a JSONPath-style pointer into the
 * document and {@link #node()} is the compact JSON of that node, both of which are surfaced in the
 * {@code application/problem+json} body of a rejected write.
 */
public class RuleValidationException extends RuntimeException {

    private final String path;
    private final String node;

    public RuleValidationException(String path, String node, String message) {
        super(message);
        this.path = path == null || path.isBlank() ? "$" : path;
        this.node = node;
    }

    public RuleValidationException(String path, String message) {
        this(path, null, message);
    }

    /** JSONPath-style pointer to the bad node, e.g. {@code $.conditions[1].conditions[0]}. */
    public String path() {
        return path;
    }

    /** Compact JSON of the bad node, or {@code null} when the document could not be read at all. */
    public String node() {
        return node;
    }

    /** Message prefixed with the node pointer, for logs and for the problem detail. */
    public String describe() {
        return "Invalid rule logic at " + path + ": " + getMessage();
    }
}
