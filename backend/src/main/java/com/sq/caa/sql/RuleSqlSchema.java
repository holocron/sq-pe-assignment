package com.sq.caa.sql;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The only names an agent-authored fragment may use: five relations and their columns.
 *
 * <p>These are not tables. They are the CTEs {@link RuleSqlWrapper} defines around the fragment,
 * already filtered to the customer under analysis, so a fragment written against this schema cannot
 * express a cross-customer question at all - there is no customer to name.
 *
 * <p>The column lists mirror {@code caa_ro.*} in {@code V5__readonly_role.sql} exactly, which is
 * also what the CTEs project. {@code RuleSqlWrapperTest} asserts that correspondence, because a
 * silent drift here would either reject a legal fragment or let an illegal identifier through to
 * the planner.
 */
public final class RuleSqlSchema {

    /** The customer under analysis, exactly one row. */
    public static final String CUSTOMER = "customer";

    /** Every transaction of that customer. */
    public static final String TX = "tx";

    /** Card detail of those transactions. */
    public static final String CARD = "card";

    /** Payment detail of those transactions. */
    public static final String PAYMENT = "payment";

    /** Crypto detail of those transactions. */
    public static final String CRYPTO = "crypto";

    /**
     * The column a fragment has to project, because it is how a match is reported back. The wrapper
     * joins it to the customer's transactions; anything else the fragment selects is discarded.
     */
    public static final String MATCH_COLUMN = "transaction_id";

    private static final Map<String, List<String>> RELATIONS = relations();

    private static final Set<String> ALL_COLUMNS = RELATIONS.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    private RuleSqlSchema() {
    }

    private static Map<String, List<String>> relations() {
        Map<String, List<String>> relations = new LinkedHashMap<>();
        relations.put(CUSTOMER, List.of("customer_id", "last_name", "first_name", "dob", "country"));
        relations.put(TX, List.of("transaction_id", "customer_id", "activity_type", "amount",
                "currency", "status", "created_at"));
        relations.put(CARD, List.of("transaction_id", "card_pan", "card_type", "merchant_name",
                "mcc_code", "card_present", "authorization_code", "decline_reason"));
        relations.put(PAYMENT, List.of("transaction_id", "payment_method", "sender_account",
                "receiver_account", "receiver_bank_country"));
        relations.put(CRYPTO, List.of("transaction_id", "blockchain", "wallet_address_from",
                "wallet_address_to", "tx_hash", "exchange_name"));
        return Map.copyOf(relations);
    }

    /** The five relation names, in the order the wrapper declares them. */
    public static Set<String> relationNames() {
        return RELATIONS.keySet();
    }

    /** Whether {@code name} is one of the five relations. */
    public static boolean isRelation(String name) {
        return RELATIONS.containsKey(name);
    }

    /** Columns of one relation, empty when the name is not a relation. */
    public static List<String> columnsOf(String relation) {
        return RELATIONS.getOrDefault(relation, List.of());
    }

    /** Whether {@code column} is a column of any of the five relations. */
    public static boolean isColumn(String column) {
        return ALL_COLUMNS.contains(column);
    }

    /**
     * The schema as one line, for rejection messages. A model that named something that does not
     * exist needs to be told what does, in the same breath, or it will guess again.
     */
    public static String describe() {
        return RELATIONS.entrySet().stream()
                .map(entry -> entry.getKey() + "(" + String.join(", ", entry.getValue()) + ")")
                .collect(Collectors.joining("; "));
    }
}
