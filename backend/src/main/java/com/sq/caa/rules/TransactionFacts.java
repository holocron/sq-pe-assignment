package com.sq.caa.rules;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.CardActivity;
import com.sq.caa.domain.CryptoActivity;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.PaymentActivity;
import com.sq.caa.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Every catalog field of one transaction, resolved once.
 *
 * <p>Values are materialised eagerly at batch build time, so quoting a transaction to the model - or
 * rendering the evidence of m rules over n transactions - never touches an entity again: readers
 * only see this map. A field that does not belong to the transaction's activity type is absent from
 * it, which is what lets {@link #lookup(String)} tell "not applicable here" apart from "unknown
 * field" and keeps an empty value distinguishable from a value the model was never shown.
 */
public final class TransactionFacts {

    private final UUID transactionId;
    private final ActivityType activityType;
    private final Instant createdAt;
    private final BigDecimal amount;
    private final String currency;
    private final Map<String, Object> values;

    private TransactionFacts(UUID transactionId, ActivityType activityType, Instant createdAt,
            BigDecimal amount, String currency, Map<String, Object> values) {
        this.transactionId = transactionId;
        this.activityType = activityType;
        this.createdAt = createdAt;
        this.amount = amount;
        this.currency = currency;
        this.values = values;
    }

    /**
     * Resolves every field of the catalog for one transaction. Never throws: a detail row that
     * cannot be read leaves its fields present-but-null, which is reported to the model as an empty
     * value rather than silently omitted.
     */
    public static TransactionFacts of(Transaction transaction, Customer customer, AggregateSnapshot aggregates) {
        Map<String, Object> values = new HashMap<>(48);
        ActivityType activityType = transaction.getActivityType();
        Instant createdAt = transaction.getCreatedAt();

        values.put(FieldCatalog.AMOUNT, transaction.getAmount());
        values.put(FieldCatalog.CURRENCY, text(transaction.getCurrency()));
        values.put(FieldCatalog.STATUS, text(transaction.getStatus()));
        values.put(FieldCatalog.ACTIVITY_TYPE, activityType == null ? null : activityType.name());
        values.put(FieldCatalog.CREATED_AT, createdAt);
        values.put(FieldCatalog.HOUR_OF_DAY,
                createdAt == null ? null : BigDecimal.valueOf(createdAt.atZone(ZoneOffset.UTC).getHour()));

        Customer owner = customer != null ? customer : safeCustomer(transaction);
        values.put(FieldCatalog.CUSTOMER_COUNTRY, owner == null ? null : text(owner.getCountry()));
        values.put(FieldCatalog.CUSTOMER_AGE, owner == null || owner.getAge() == null
                ? null : BigDecimal.valueOf(owner.getAge()));

        if (activityType == ActivityType.CARD) {
            CardActivity card = safeCard(transaction);
            values.put(FieldCatalog.CARD_MCC_CODE, card == null ? null : text(card.getMccCode()));
            values.put(FieldCatalog.CARD_CARD_TYPE, card == null ? null : text(card.getCardType()));
            values.put(FieldCatalog.CARD_CARD_PRESENT, card == null ? null : card.isCardPresent());
            values.put(FieldCatalog.CARD_MERCHANT_NAME, card == null ? null : text(card.getMerchantName()));
            values.put(FieldCatalog.CARD_DECLINE_REASON, card == null ? null : text(card.getDeclineReason()));
        } else if (activityType == ActivityType.PAYMENT) {
            PaymentActivity payment = safePayment(transaction);
            values.put(FieldCatalog.PAYMENT_METHOD, payment == null ? null : text(payment.getPaymentMethod()));
            values.put(FieldCatalog.PAYMENT_RECEIVER_BANK_COUNTRY,
                    payment == null ? null : text(payment.getReceiverBankCountry()));
            values.put(FieldCatalog.PAYMENT_SENDER_ACCOUNT, payment == null ? null : text(payment.getSenderAccount()));
            values.put(FieldCatalog.PAYMENT_RECEIVER_ACCOUNT,
                    payment == null ? null : text(payment.getReceiverAccount()));
        } else if (activityType == ActivityType.CRYPTO) {
            CryptoActivity crypto = safeCrypto(transaction);
            values.put(FieldCatalog.CRYPTO_BLOCKCHAIN, crypto == null ? null : text(crypto.getBlockchain()));
            values.put(FieldCatalog.CRYPTO_EXCHANGE_NAME, crypto == null ? null : text(crypto.getExchangeName()));
            values.put(FieldCatalog.CRYPTO_WALLET_ADDRESS_TO,
                    crypto == null ? null : text(crypto.getWalletAddressTo()));
        }

        AggregateSnapshot snapshot = aggregates == null ? AggregateSnapshot.EMPTY : aggregates;
        values.put(FieldCatalog.AGG_TX_COUNT_24H, BigDecimal.valueOf(snapshot.txCount24h()));
        values.put(FieldCatalog.AGG_AMOUNT_SUM_24H, snapshot.amountSum24h());
        values.put(FieldCatalog.AGG_FAILED_COUNT_24H, BigDecimal.valueOf(snapshot.failedCount24h()));
        values.put(FieldCatalog.AGG_DISTINCT_COUNTRIES_30D, BigDecimal.valueOf(snapshot.distinctCountries30d()));
        values.put(FieldCatalog.AGG_CRYPTO_RATIO_30D, snapshot.cryptoRatio30d());
        values.put(FieldCatalog.AGG_MAX_AMOUNT_30D, snapshot.maxAmount30d());

        return new TransactionFacts(transaction.getTransactionId(), activityType, createdAt,
                transaction.getAmount(), text(transaction.getCurrency()), Map.copyOf(nullSafe(values)));
    }

    /** Resolves one catalog field name against this transaction. */
    public FieldLookup lookup(String field) {
        if (field == null) {
            return FieldLookup.UNKNOWN;
        }
        String key = field.trim();
        if (!FieldCatalog.contains(key)) {
            return FieldLookup.UNKNOWN;
        }
        if (!values.containsKey(key)) {
            return FieldLookup.NOT_APPLICABLE;
        }
        Object value = values.get(key);
        return value == NULL_MARKER ? FieldLookup.NULL_VALUE : FieldLookup.resolved(value);
    }

    public UUID transactionId() {
        return transactionId;
    }

    public ActivityType activityType() {
        return activityType;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    // ------------------------------------------------------------------
    // Map.copyOf rejects null values, so nulls are stored as a marker and
    // translated back on lookup.
    // ------------------------------------------------------------------

    private static final Object NULL_MARKER = new Object();

    private static Map<String, Object> nullSafe(Map<String, Object> values) {
        Map<String, Object> copy = new HashMap<>(values.size() * 2);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() == null ? NULL_MARKER : entry.getValue());
        }
        return copy;
    }

    /** Trims text and treats blank as absent, matching how the repository queries read these columns. */
    private static String text(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Customer safeCustomer(Transaction transaction) {
        try {
            return transaction.getCustomer();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static CardActivity safeCard(Transaction transaction) {
        try {
            return transaction.getCardActivity();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static PaymentActivity safePayment(Transaction transaction) {
        try {
            return transaction.getPaymentActivity();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static CryptoActivity safeCrypto(Transaction transaction) {
        try {
            return transaction.getCryptoActivity();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
