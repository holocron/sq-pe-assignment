package com.sq.caa.rules;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.Transaction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Computes the {@code agg.*} values for every transaction of one customer in a single pass.
 *
 * <p>The aggregates are defined relative to each transaction, so the naive implementation is one
 * pair of SQL window queries per transaction per rule - O(n*m) round trips for a customer with n
 * transactions and m rules. Instead the customer's transactions are loaded once per evaluation batch
 * and every window is derived here with two forward-only pointers, which is O(n log n) for the sort
 * and O(n) for the sweep, with no database access at all.
 */
public final class AggregateCalculator {

    private static final Duration WINDOW_24H = Duration.ofHours(24);
    private static final Duration WINDOW_30D = Duration.ofDays(30);
    private static final String STATUS_FAILED = Transaction.STATUS_FAILED;
    private static final int RATIO_SCALE = 4;

    private AggregateCalculator() {
    }

    /**
     * @param transactions every transaction of one customer; order does not matter
     * @return snapshot per transaction id, never {@code null} entries
     */
    public static Map<UUID, AggregateSnapshot> compute(Collection<Transaction> transactions) {
        List<Transaction> ordered = new ArrayList<>();
        for (Transaction transaction : transactions) {
            if (transaction != null && transaction.getTransactionId() != null
                    && transaction.getCreatedAt() != null) {
                ordered.add(transaction);
            }
        }
        ordered.sort(Comparator.comparing(Transaction::getCreatedAt)
                .thenComparing(Transaction::getTransactionId));

        Map<UUID, AggregateSnapshot> snapshots = new HashMap<>(Math.max(16, ordered.size() * 2));

        int left24 = 0;
        int left30 = 0;
        long count24 = 0;
        long failed24 = 0;
        BigDecimal sum24 = BigDecimal.ZERO;
        long count30 = 0;
        long crypto30 = 0;
        Map<String, Integer> countries30 = new HashMap<>();
        TreeMap<BigDecimal, Integer> amounts30 = new TreeMap<>();

        for (int i = 0; i < ordered.size(); i++) {
            Transaction current = ordered.get(i);
            Instant at = current.getCreatedAt();

            count24++;
            sum24 = sum24.add(amountOf(current));
            if (isFailed(current)) {
                failed24++;
            }
            count30++;
            if (current.getActivityType() == ActivityType.CRYPTO) {
                crypto30++;
            }
            String country = receiverCountry(current);
            if (country != null) {
                countries30.merge(country, 1, Integer::sum);
            }
            amounts30.merge(amountOf(current), 1, Integer::sum);

            Instant from24 = at.minus(WINDOW_24H);
            while (left24 < i && !ordered.get(left24).getCreatedAt().isAfter(from24)) {
                Transaction leaving = ordered.get(left24);
                count24--;
                sum24 = sum24.subtract(amountOf(leaving));
                if (isFailed(leaving)) {
                    failed24--;
                }
                left24++;
            }

            Instant from30 = at.minus(WINDOW_30D);
            while (left30 < i && !ordered.get(left30).getCreatedAt().isAfter(from30)) {
                Transaction leaving = ordered.get(left30);
                count30--;
                if (leaving.getActivityType() == ActivityType.CRYPTO) {
                    crypto30--;
                }
                String leavingCountry = receiverCountry(leaving);
                if (leavingCountry != null) {
                    countries30.compute(leavingCountry, (key, value) -> value == null || value <= 1 ? null : value - 1);
                }
                amounts30.compute(amountOf(leaving), (key, value) -> value == null || value <= 1 ? null : value - 1);
                left30++;
            }

            BigDecimal cryptoRatio = count30 == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(crypto30).divide(BigDecimal.valueOf(count30), RATIO_SCALE,
                            RoundingMode.HALF_UP);
            BigDecimal maxAmount = amounts30.isEmpty() ? BigDecimal.ZERO : amounts30.lastKey();

            snapshots.put(current.getTransactionId(), new AggregateSnapshot(
                    count24, sum24, failed24, countries30.size(), cryptoRatio, maxAmount));
        }
        return snapshots;
    }

    private static BigDecimal amountOf(Transaction transaction) {
        return transaction.getAmount() == null ? BigDecimal.ZERO : transaction.getAmount();
    }

    private static boolean isFailed(Transaction transaction) {
        return STATUS_FAILED.equalsIgnoreCase(transaction.getStatus());
    }

    private static String receiverCountry(Transaction transaction) {
        try {
            if (transaction.getActivityType() != ActivityType.PAYMENT
                    || transaction.getPaymentActivity() == null) {
                return null;
            }
            String country = transaction.getPaymentActivity().getReceiverBankCountry();
            return country == null || country.isBlank() ? null : country.trim().toUpperCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            // A detail row that cannot be read (for example an uninitialised proxy) must not break
            // the sweep; the country simply does not contribute to the distinct count.
            return null;
        }
    }
}
