package com.sq.caa.rules;

/**
 * Thrown when a rule cannot be saved as written.
 *
 * <p>Conditions are prose, so there is no node to point at any more: the exception names the
 * offending <em>field</em> of the request ({@code thresholdLogic}, {@code ruleName}, {@code weight})
 * and says in one sentence what is wrong with it. Both are surfaced in the
 * {@code application/problem+json} body of a rejected write so the editor can highlight the input
 * that needs fixing.
 */
public class RuleValidationException extends RuntimeException {

    private final String field;

    public RuleValidationException(String field, String message) {
        super(message);
        this.field = field == null || field.isBlank() ? "thresholdLogic" : field.trim();
    }

    /** Request field the problem belongs to, e.g. {@code thresholdLogic}. */
    public String field() {
        return field;
    }

    /** Message prefixed with the field name, for logs and for the problem detail. */
    public String describe() {
        return "Invalid rule: " + field + " " + getMessage();
    }
}
