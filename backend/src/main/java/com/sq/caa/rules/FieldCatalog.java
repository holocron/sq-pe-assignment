package com.sq.caa.rules;

import com.sq.caa.domain.RuleScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single source of truth for the rule DSL field catalog.
 *
 * <p>Three consumers read it and must never disagree: the evaluator (which resolves these exact
 * names against a transaction), the write-time validator (which rejects rules referencing anything
 * else) and the frontend rule editor (served through {@code GET /api/rules/field-catalog}).
 *
 * <p>Field names are the ones fixed by the build spec and are stable API - renaming one breaks every
 * stored rule.
 */
public final class FieldCatalog {

    // Base transaction fields -------------------------------------------------
    public static final String AMOUNT = "amount";
    public static final String CURRENCY = "currency";
    public static final String STATUS = "status";
    public static final String ACTIVITY_TYPE = "activity_type";
    public static final String CREATED_AT = "created_at";

    // Derived -----------------------------------------------------------------
    public static final String HOUR_OF_DAY = "hour_of_day";
    public static final String CUSTOMER_COUNTRY = "customer.country";
    public static final String CUSTOMER_AGE = "customer.age";

    // Card --------------------------------------------------------------------
    public static final String CARD_MCC_CODE = "card.mcc_code";
    public static final String CARD_CARD_TYPE = "card.card_type";
    public static final String CARD_CARD_PRESENT = "card.card_present";
    public static final String CARD_MERCHANT_NAME = "card.merchant_name";
    public static final String CARD_DECLINE_REASON = "card.decline_reason";

    // Payment -----------------------------------------------------------------
    public static final String PAYMENT_METHOD = "payment.payment_method";
    public static final String PAYMENT_RECEIVER_BANK_COUNTRY = "payment.receiver_bank_country";
    public static final String PAYMENT_SENDER_ACCOUNT = "payment.sender_account";
    public static final String PAYMENT_RECEIVER_ACCOUNT = "payment.receiver_account";

    // Crypto ------------------------------------------------------------------
    public static final String CRYPTO_BLOCKCHAIN = "crypto.blockchain";
    public static final String CRYPTO_EXCHANGE_NAME = "crypto.exchange_name";
    public static final String CRYPTO_WALLET_ADDRESS_TO = "crypto.wallet_address_to";

    // Customer-level aggregates ----------------------------------------------
    public static final String AGG_TX_COUNT_24H = "agg.tx_count_24h";
    public static final String AGG_AMOUNT_SUM_24H = "agg.amount_sum_24h";
    public static final String AGG_FAILED_COUNT_24H = "agg.failed_count_24h";
    public static final String AGG_DISTINCT_COUNTRIES_30D = "agg.distinct_countries_30d";
    public static final String AGG_CRYPTO_RATIO_30D = "agg.crypto_ratio_30d";
    public static final String AGG_MAX_AMOUNT_30D = "agg.max_amount_30d";

    private static final List<FieldDefinition> ENTRIES = List.of(
            new FieldDefinition(AMOUNT, "Amount", FieldType.NUMBER, RuleScope.ALL,
                    List.of(), false, false,
                    "Transaction amount in the transaction currency."),
            new FieldDefinition(CURRENCY, "Currency", FieldType.STRING, RuleScope.ALL,
                    List.of(), false, false,
                    "ISO currency code or crypto ticker of the transaction."),
            new FieldDefinition(STATUS, "Status", FieldType.ENUM, RuleScope.ALL,
                    List.of("Completed", "Pending", "Failed", "Reversed"), true, false,
                    "Processing outcome of the transaction."),
            new FieldDefinition(ACTIVITY_TYPE, "Activity type", FieldType.ENUM, RuleScope.ALL,
                    List.of("CARD", "PAYMENT", "CRYPTO"), true, false,
                    "Kind of activity the transaction represents."),
            new FieldDefinition(CREATED_AT, "Created at (UTC)", FieldType.DATETIME, RuleScope.ALL,
                    List.of(), false, false,
                    "Instant the transaction occurred, compared against ISO-8601 values."),
            new FieldDefinition(HOUR_OF_DAY, "Hour of day (UTC)", FieldType.NUMBER, RuleScope.ALL,
                    List.of(), false, false,
                    "Derived: hour 0-23 of created_at in UTC."),
            new FieldDefinition(CUSTOMER_COUNTRY, "Customer country", FieldType.STRING, RuleScope.ALL,
                    List.of(), false, false,
                    "ISO 3166-1 alpha-2 country of the customer."),
            new FieldDefinition(CUSTOMER_AGE, "Customer age", FieldType.NUMBER, RuleScope.ALL,
                    List.of(), false, false,
                    "Derived: full years between the customer date of birth and today."),

            new FieldDefinition(CARD_MCC_CODE, "Card MCC code", FieldType.STRING, RuleScope.CARD,
                    List.of(), false, false,
                    "Four digit merchant category code of the card acceptor."),
            new FieldDefinition(CARD_CARD_TYPE, "Card type", FieldType.ENUM, RuleScope.CARD,
                    List.of("Debit", "Credit", "Prepaid"), true, false,
                    "Product type of the card used."),
            new FieldDefinition(CARD_CARD_PRESENT, "Card present", FieldType.BOOLEAN, RuleScope.CARD,
                    List.of("true", "false"), true, false,
                    "False means a card-not-present authorisation."),
            new FieldDefinition(CARD_MERCHANT_NAME, "Merchant name", FieldType.STRING, RuleScope.CARD,
                    List.of(), false, false,
                    "Name of the merchant the card was used at."),
            new FieldDefinition(CARD_DECLINE_REASON, "Decline reason", FieldType.STRING, RuleScope.CARD,
                    List.of(), false, true,
                    "Populated only for declined authorisations, empty otherwise."),

            new FieldDefinition(PAYMENT_METHOD, "Payment method", FieldType.ENUM, RuleScope.PAYMENT,
                    List.of("ACH", "Wire", "SWIFT", "P2P"), true, false,
                    "Rail the payment was sent over."),
            new FieldDefinition(PAYMENT_RECEIVER_BANK_COUNTRY, "Receiver bank country",
                    FieldType.STRING, RuleScope.PAYMENT, List.of(), false, false,
                    "ISO 3166-1 alpha-2 country of the beneficiary bank."),
            new FieldDefinition(PAYMENT_SENDER_ACCOUNT, "Sender account", FieldType.STRING,
                    RuleScope.PAYMENT, List.of(), false, false,
                    "Ordering account identifier."),
            new FieldDefinition(PAYMENT_RECEIVER_ACCOUNT, "Receiver account", FieldType.STRING,
                    RuleScope.PAYMENT, List.of(), false, false,
                    "Beneficiary account identifier."),

            new FieldDefinition(CRYPTO_BLOCKCHAIN, "Blockchain", FieldType.ENUM, RuleScope.CRYPTO,
                    List.of("BTC", "ETH", "USDT", "XMR", "LTC", "TRX", "SOL", "BNB"), false, false,
                    "Chain or token the transfer settled on; the list is a suggestion, not a closed set."),
            new FieldDefinition(CRYPTO_EXCHANGE_NAME, "Exchange name", FieldType.STRING,
                    RuleScope.CRYPTO, List.of(), false, true,
                    "Counterparty exchange, empty when the counterparty is unattributed."),
            new FieldDefinition(CRYPTO_WALLET_ADDRESS_TO, "Destination wallet", FieldType.STRING,
                    RuleScope.CRYPTO, List.of(), false, false,
                    "Destination wallet address of the transfer."),

            new FieldDefinition(AGG_TX_COUNT_24H, "Transactions in previous 24h", FieldType.NUMBER,
                    RuleScope.ALL, List.of(), false, false,
                    "Customer-level: transactions in the 24h window ending at this transaction, itself included."),
            new FieldDefinition(AGG_AMOUNT_SUM_24H, "Amount sum in previous 24h", FieldType.NUMBER,
                    RuleScope.ALL, List.of(), false, false,
                    "Customer-level: summed amount over the same 24h window (no FX conversion)."),
            new FieldDefinition(AGG_FAILED_COUNT_24H, "Failed transactions in previous 24h",
                    FieldType.NUMBER, RuleScope.ALL, List.of(), false, false,
                    "Customer-level: transactions with status Failed in the same 24h window."),
            new FieldDefinition(AGG_DISTINCT_COUNTRIES_30D, "Distinct receiver countries in 30d",
                    FieldType.NUMBER, RuleScope.ALL, List.of(), false, false,
                    "Customer-level: distinct beneficiary bank countries of payments in the 30 day window."),
            new FieldDefinition(AGG_CRYPTO_RATIO_30D, "Crypto share of activity in 30d",
                    FieldType.NUMBER, RuleScope.ALL, List.of(), false, false,
                    "Customer-level: crypto transactions divided by all transactions in the 30 day window, 0..1."),
            new FieldDefinition(AGG_MAX_AMOUNT_30D, "Largest amount in 30d", FieldType.NUMBER,
                    RuleScope.ALL, List.of(), false, false,
                    "Customer-level: largest single amount in the 30 day window."));

    private static final Map<String, FieldDefinition> BY_NAME = index();

    private FieldCatalog() {
    }

    private static Map<String, FieldDefinition> index() {
        Map<String, FieldDefinition> map = new LinkedHashMap<>();
        for (FieldDefinition definition : ENTRIES) {
            map.put(definition.field(), definition);
        }
        return Map.copyOf(map);
    }

    /** The whole catalog, in editor display order. */
    public static List<FieldDefinition> entries() {
        return ENTRIES;
    }

    /** Catalog entries usable by a rule with the given scope. */
    public static List<FieldDefinition> entriesFor(RuleScope scope) {
        return ENTRIES.stream().filter(definition -> definition.availableIn(scope)).toList();
    }

    public static Optional<FieldDefinition> find(String field) {
        return field == null ? Optional.empty() : Optional.ofNullable(BY_NAME.get(field.trim()));
    }

    public static boolean contains(String field) {
        return find(field).isPresent();
    }

    /** Every field name, used by error messages and by the tests that guard the contract. */
    public static List<String> fieldNames() {
        return ENTRIES.stream().map(FieldDefinition::field).toList();
    }
}
