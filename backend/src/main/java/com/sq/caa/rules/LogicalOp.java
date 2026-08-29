package com.sq.caa.rules;

import java.util.Locale;
import java.util.Optional;

/** Boolean combinator of a DSL group node. */
public enum LogicalOp {

    /** Every child must match. */
    AND,
    /** At least one child must match. */
    OR,
    /** Negation of the conjunction of the children (with one child, plain negation). */
    NOT;

    public static Optional<LogicalOp> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        for (LogicalOp op : values()) {
            if (op.name().equals(normalised)) {
                return Optional.of(op);
            }
        }
        return Optional.empty();
    }
}
