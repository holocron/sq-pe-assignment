package com.sq.caa.rules;

import java.util.Locale;
import java.util.Optional;

/**
 * Comparison operators of the risk-rule DSL.
 *
 * <p>The set is fixed by the shared contract in the build spec; the backend evaluator and the
 * frontend visual editor both work from it. {@link Arity} tells a client how many values the
 * operator expects, which is what drives the value widget in the editor.
 */
public enum RuleOperator {

    GT("greater than", Arity.SINGLE),
    GTE("greater than or equal", Arity.SINGLE),
    LT("less than", Arity.SINGLE),
    LTE("less than or equal", Arity.SINGLE),
    EQ("equals", Arity.SINGLE),
    NEQ("does not equal", Arity.SINGLE),
    IN("is one of", Arity.LIST),
    NOT_IN("is not one of", Arity.LIST),
    CONTAINS("contains", Arity.SINGLE),
    NOT_CONTAINS("does not contain", Arity.SINGLE),
    BETWEEN("between", Arity.PAIR),
    IS_NULL("is empty", Arity.NONE),
    NOT_NULL("is present", Arity.NONE),
    MATCHES("matches regex", Arity.SINGLE);

    /** How many operands the operator consumes. */
    public enum Arity {
        /** No {@code value} at all ({@code IS_NULL}, {@code NOT_NULL}). */
        NONE,
        /** A single scalar {@code value}. */
        SINGLE,
        /** Exactly two values, given as a 2-element array ({@code BETWEEN}). */
        PAIR,
        /** A non-empty array of values ({@code IN}, {@code NOT_IN}). */
        LIST
    }

    private final String label;
    private final Arity arity;

    RuleOperator(String label, Arity arity) {
        this.label = label;
        this.arity = arity;
    }

    public String label() {
        return label;
    }

    public Arity arity() {
        return arity;
    }

    /** {@code true} for the two operators that are defined for a missing value. */
    public boolean isNullCheck() {
        return this == IS_NULL || this == NOT_NULL;
    }

    /** Lenient lookup: case-insensitive, tolerates surrounding whitespace. */
    public static Optional<RuleOperator> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (RuleOperator operator : values()) {
            if (operator.name().equals(normalised)) {
                return Optional.of(operator);
            }
        }
        return Optional.empty();
    }
}
