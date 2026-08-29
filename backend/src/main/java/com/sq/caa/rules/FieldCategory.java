package com.sq.caa.rules;

import java.util.Locale;

/**
 * How the reference panel groups the field catalog.
 *
 * <p>Serialised in lower case on the wire ({@code transaction}, {@code card}, ...), which is the
 * vocabulary the rule editor groups by.
 */
public enum FieldCategory {

    /** Columns of {@code transactions} itself, plus values derived from them. */
    TRANSACTION,

    /** Attributes of the customer the activity belongs to. */
    CUSTOMER,

    /** {@code card_activity} detail. */
    CARD,

    /** {@code payment_activity} detail. */
    PAYMENT,

    /** {@code crypto_activity} detail. */
    CRYPTO,

    /** Customer-level windowed values computed over the whole activity, not one row. */
    AGGREGATE;

    /** The name as the frontend expects it. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
