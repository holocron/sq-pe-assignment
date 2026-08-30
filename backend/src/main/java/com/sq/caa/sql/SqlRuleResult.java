package com.sq.caa.sql;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of running one agent-authored rule query.
 *
 * <p>This is the record the verdict is derived from, and the derivation is mechanical: the rule
 * fired if and only if {@code ok} is true and {@code matchedCount} is greater than zero. Nothing in
 * this record is an opinion - {@code matchedTransactionIds} are the ids PostgreSQL returned,
 * intersected with the transactions of the customer under analysis, and {@code matchedCount} is the
 * count the database computed, not a length the model estimated.
 *
 * <p>{@code ok == false} means the rule was <b>not judged</b>. It is never "not triggered": a
 * fragment the validator refused and a fragment PostgreSQL could not run are both failures to
 * obtain an answer, and the coverage guarantee treats them as unjudged rules.
 *
 * @param matchedTransactionIds ids of the matched transactions, most recent first, truncated to the
 *                              configured cap. Always empty when {@code ok} is false.
 * @param matchedCount          the true number of distinct matched transactions, even when the id
 *                              list above was truncated.
 * @param capped                true when the id list is shorter than {@code matchedCount}.
 * @param rejectionReason       why the validator refused the fragment, phrased so the model can fix
 *                              it and retry; null when the fragment passed validation.
 * @param errorMessage          the PostgreSQL error, trimmed to one readable line; null when the
 *                              query ran.
 * @param effectiveSql          the full wrapped statement that was actually sent to the database,
 *                              for the audit trail. Null when nothing was sent because validation
 *                              refused the fragment first.
 * @param ms                    wall-clock milliseconds spent in {@code evaluate}, validation and
 *                              connection acquisition included.
 */
public record SqlRuleResult(
        boolean ok,
        List<UUID> matchedTransactionIds,
        int matchedCount,
        boolean capped,
        String rejectionReason,
        String errorMessage,
        String effectiveSql,
        long ms) {

    /** Defensive copy: the id list is part of an audit record and must not change after the fact. */
    public SqlRuleResult {
        matchedTransactionIds = matchedTransactionIds == null
                ? List.of()
                : List.copyOf(matchedTransactionIds);
    }

    /** The fragment never reached the database. */
    static SqlRuleResult rejected(String reason, long ms) {
        return new SqlRuleResult(false, List.of(), 0, false, reason, null, null, ms);
    }

    /** The statement ran and PostgreSQL refused it, or it timed out. */
    static SqlRuleResult failed(String error, String effectiveSql, long ms) {
        return new SqlRuleResult(false, List.of(), 0, false, null, error, effectiveSql, ms);
    }

    /** The statement ran. Zero matches is a successful evaluation with a "not triggered" verdict. */
    static SqlRuleResult evaluated(List<UUID> ids, int matchedCount, String effectiveSql, long ms) {
        return new SqlRuleResult(
                true, ids, matchedCount, matchedCount > ids.size(), null, null, effectiveSql, ms);
    }
}
