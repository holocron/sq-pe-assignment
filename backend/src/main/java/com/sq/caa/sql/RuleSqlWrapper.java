package com.sq.caa.sql;

/**
 * Builds the statement that actually runs: the agent's fragment nested inside CTEs that are already
 * restricted to one customer.
 *
 * <p>The customer is bound twice as a JDBC parameter and never once concatenated, so the fragment
 * has no reachable position in which quoting could matter. The five names it may use - {@code
 * customer}, {@code tx}, {@code card}, {@code payment}, {@code crypto} - are CTEs, not tables; the
 * base tables are not named in a place the fragment can reach, and the read-only role holds no
 * privilege on them anyway.
 *
 * <p>Two properties are worth stating precisely, because the verdict rests on them:
 *
 * <ol>
 *   <li><b>The fragment cannot widen its own scope.</b> Its result is joined back to {@code tx},
 *       which is the parameter-bound list of this customer's transactions. Any id it produces from
 *       anywhere else - a smuggled table, a UNION, a literal UUID typed out in full - is dropped by
 *       that join before it can be returned or counted.
 *   <li><b>The count is the database's, not the model's.</b> {@code matched_total} is a
 *       {@code count(*)} over every distinct match; the {@code LIMIT} truncates the ids carried back
 *       over the wire, never the number reported.
 * </ol>
 *
 * <p>The CTEs are {@code MATERIALIZED} deliberately. Inlining would let the planner re-evaluate the
 * customer filter underneath expressions supplied by the fragment; materialising computes the scope
 * once, up front, from the bound parameter.
 */
public final class RuleSqlWrapper {

    /** Number of JDBC parameters in the wrapped statement, both of them the customer id. */
    public static final int PARAMETERS = 2;

    private static final String PREFIX = """
            WITH customer AS MATERIALIZED (
                SELECT c.customer_id, c.last_name, c.first_name, c.dob, c.country
                FROM caa_ro.customers c
                WHERE c.customer_id = ?
            ),
            tx AS MATERIALIZED (
                SELECT t.transaction_id, t.customer_id, t.activity_type, t.amount, t.currency,
                       t.status, t.created_at
                FROM caa_ro.transactions t
                WHERE t.customer_id = ?
            ),
            card AS MATERIALIZED (
                SELECT ca.transaction_id, ca.card_pan, ca.card_type, ca.merchant_name, ca.mcc_code,
                       ca.card_present, ca.authorization_code, ca.decline_reason
                FROM caa_ro.card_activity ca
                JOIN tx ON tx.transaction_id = ca.transaction_id
            ),
            payment AS MATERIALIZED (
                SELECT pa.transaction_id, pa.payment_method, pa.sender_account, pa.receiver_account,
                       pa.receiver_bank_country
                FROM caa_ro.payment_activity pa
                JOIN tx ON tx.transaction_id = pa.transaction_id
            ),
            crypto AS MATERIALIZED (
                SELECT cr.transaction_id, cr.blockchain, cr.wallet_address_from,
                       cr.wallet_address_to, cr.tx_hash, cr.exchange_name
                FROM caa_ro.crypto_activity cr
                JOIN tx ON tx.transaction_id = cr.transaction_id
            ),
            rule_result AS (
            """;

    private static final String SUFFIX = """

            ),
            matched AS (
                SELECT DISTINCT scope.transaction_id, scope.created_at
                FROM rule_result agent_rows
                JOIN tx scope ON scope.transaction_id = agent_rows.transaction_id
            )
            SELECT m.transaction_id, (SELECT count(*) FROM matched) AS matched_total
            FROM matched m
            ORDER BY m.created_at DESC, m.transaction_id
            LIMIT %d
            """;

    private RuleSqlWrapper() {
    }

    /**
     * Wraps a fragment the validator has already accepted.
     *
     * @param fragment the accepted SELECT fragment, without a trailing semicolon
     * @param idCap    how many ids the statement may carry back; the reported count is unaffected
     */
    public static String wrap(String fragment, int idCap) {
        return PREFIX + fragment + SUFFIX.formatted(Math.max(1, idCap));
    }
}
