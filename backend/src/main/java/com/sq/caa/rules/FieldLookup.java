package com.sq.caa.rules;

/**
 * Outcome of resolving one catalog field against one transaction.
 *
 * <p>The four states are deliberately distinct because they mean different things for degradation:
 * an unknown field or a missing value is a defect worth flagging, while a field that simply does not
 * exist on this activity type (a {@code payment.*} field on a card transaction) is an ordinary,
 * expected non-match.
 */
public record FieldLookup(FieldLookup.Status status, Object value) {

    public enum Status {
        /** Field exists for this transaction and has a value. */
        RESOLVED,
        /** Field exists for this transaction but the value is null or blank. */
        NULL_VALUE,
        /** Field is in the catalog but not part of this transaction's activity type. */
        NOT_APPLICABLE,
        /** Field is not in the catalog at all. */
        UNKNOWN_FIELD
    }

    static final FieldLookup UNKNOWN = new FieldLookup(Status.UNKNOWN_FIELD, null);
    static final FieldLookup NOT_APPLICABLE = new FieldLookup(Status.NOT_APPLICABLE, null);
    static final FieldLookup NULL_VALUE = new FieldLookup(Status.NULL_VALUE, null);

    static FieldLookup resolved(Object value) {
        return value == null ? NULL_VALUE : new FieldLookup(Status.RESOLVED, value);
    }

    public boolean isResolved() {
        return status == Status.RESOLVED;
    }

    /** True when the field carries no value, whether because it is null or not applicable. */
    public boolean isAbsent() {
        return status == Status.NULL_VALUE || status == Status.NOT_APPLICABLE;
    }
}
