package com.sq.caa.rules;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Lenient, never-throwing coercions shared by the validator (which reports a mismatch as a 400) and
 * the evaluator (which reports it as a degraded false).
 */
final class Values {

    private Values() {
    }

    /** Numbers, and strings that are numbers. Anything else is a mismatch. */
    static Optional<BigDecimal> toDecimal(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof BigDecimal decimal) {
            return Optional.of(decimal);
        }
        if (value instanceof Number number) {
            return Optional.of(new BigDecimal(number.toString()));
        }
        if (value instanceof Boolean) {
            return Optional.empty();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(text));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Instants, ISO-8601 strings (instant, offset, local date-time, plain date) and epoch millis. */
    static Optional<Instant> toInstant(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Instant instant) {
            return Optional.of(instant);
        }
        if (value instanceof Number number) {
            return Optional.of(Instant.ofEpochMilli(number.longValue()));
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(text));
        } catch (RuntimeException ignored) {
            // fall through to the other ISO shapes
        }
        try {
            return Optional.of(OffsetDateTime.parse(text).toInstant());
        } catch (RuntimeException ignored) {
            // fall through
        }
        try {
            return Optional.of(LocalDateTime.parse(text).toInstant(ZoneOffset.UTC));
        } catch (RuntimeException ignored) {
            // fall through
        }
        try {
            return Optional.of(LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /** Booleans and the strings {@code true}/{@code false}, case-insensitive. */
    static Optional<Boolean> toBoolean(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Boolean bool) {
            return Optional.of(bool);
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "yes", "y", "1" -> Optional.of(Boolean.TRUE);
            case "false", "no", "n", "0" -> Optional.of(Boolean.FALSE);
            default -> Optional.empty();
        };
    }

    /** String form used for text comparisons; {@code null} stays {@code null}. */
    static String toText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    /** A list operand as-is, a scalar wrapped in a singleton list, {@code null} as an empty list. */
    static List<Object> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(new ArrayList<>(list));
        }
        return List.of(value);
    }

    static boolean isList(Object value) {
        return value instanceof List<?>;
    }
}
