package com.sq.caa.domain;

/**
 * Kind of customer activity a transaction represents.
 *
 * <p>Backed by the native PostgreSQL enum type {@code activity_type}. The constant names must stay
 * identical to the labels declared in {@code V1__baseline.sql}.
 */
public enum ActivityType {
    CARD,
    PAYMENT,
    CRYPTO
}
