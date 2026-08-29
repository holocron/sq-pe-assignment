package com.sq.caa.rules;

/**
 * Value type of a catalog field.
 *
 * <p>Purely descriptive since rule conditions became prose: it tells a rule author (and the reader
 * of the reference panel) what kind of value a field holds, so a condition asks something the data
 * can actually answer - a threshold on a {@link #NUMBER}, an exact value on an {@link #ENUM}.
 */
public enum FieldType {

    /** Decimal or integral number. */
    NUMBER,

    /** Free text. */
    STRING,

    /** Text drawn from a known set of values, listed in {@link FieldDefinition#options()}. */
    ENUM,

    /** True or false. */
    BOOLEAN,

    /** Instant, always presented to the model in UTC ISO-8601. */
    DATETIME
}
