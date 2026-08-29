package com.sq.caa.rules;

import java.math.BigDecimal;

/**
 * The {@code agg.*} customer-level values as seen from one transaction.
 *
 * <p>Every window ends at the transaction itself and includes it, so a transaction always counts
 * itself in {@code txCount24h}. Windows are half-open, {@code (t - window, t]}, matching the window
 * queries on {@code TransactionRepository}.
 *
 * @param txCount24h            transactions of the customer in the 24h window
 * @param amountSum24h          summed amount over that window; amounts are summed as stored, there
 *                              is no FX conversion available
 * @param failedCount24h        transactions with status {@code Failed} in that window
 * @param distinctCountries30d  distinct beneficiary bank countries of payments in the 30 day window
 * @param cryptoRatio30d        crypto transactions divided by all transactions in the 30 day window
 * @param maxAmount30d          largest single amount in the 30 day window
 */
public record AggregateSnapshot(
        long txCount24h,
        BigDecimal amountSum24h,
        long failedCount24h,
        long distinctCountries30d,
        BigDecimal cryptoRatio30d,
        BigDecimal maxAmount30d) {

    public static final AggregateSnapshot EMPTY = new AggregateSnapshot(
            0L, BigDecimal.ZERO, 0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO);

    public AggregateSnapshot {
        amountSum24h = amountSum24h == null ? BigDecimal.ZERO : amountSum24h;
        cryptoRatio30d = cryptoRatio30d == null ? BigDecimal.ZERO : cryptoRatio30d;
        maxAmount30d = maxAmount30d == null ? BigDecimal.ZERO : maxAmount30d;
    }
}
