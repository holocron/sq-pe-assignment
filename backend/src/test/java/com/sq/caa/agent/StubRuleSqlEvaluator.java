package com.sq.caa.agent;

import com.sq.caa.sql.RuleSqlEvaluator;
import com.sq.caa.sql.SqlRuleResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A {@link RuleSqlEvaluator} that answers from a fixed script instead of from PostgreSQL.
 *
 * <p>The behaviour under test is what the agent does with a query <em>result</em>: that a rule whose
 * query returned rows is recorded as triggered whatever the model wrote alongside it, that a rule
 * whose query was refused stays unjudged, and that the run fails when it never becomes judged. None
 * of that needs a database, and testing it against one would make the assertions depend on the SQL
 * dialect and on the seeded rows rather than on the loop.
 *
 * <p>Answers are keyed by a fragment of the query text, because that is what the real evaluator sees
 * - it is handed a SELECT and a customer id, never a rule id. The fixture writes one recognisable
 * query per rule ({@link AgentTestFixtures#sqlFor}) and the test says what PostgreSQL would answer
 * for it. A query nothing was scripted for comes back {@code ok} with no rows, which is the honest
 * default: the query ran and found nothing.
 *
 * <p>The most recently registered answer wins, so a test can take the fixture's ready-made
 * evaluator and override one rule's answer without rebuilding the rest.
 *
 * <p>{@link #effectiveSql} wraps the fragment the way the real evaluator does, so a test asserting
 * what the trace recorded is asserting that the <em>executed</em> statement was kept, not the
 * fragment the model typed.
 */
final class StubRuleSqlEvaluator implements RuleSqlEvaluator {

    /** How the fragment is nested by the real evaluator before it runs. */
    static final String WRAPPER_PREFIX = "WITH customer AS (...), tx AS (...) SELECT q.transaction_id FROM (";
    static final String WRAPPER_SUFFIX = ") q JOIN tx ON tx.transaction_id = q.transaction_id";

    private final List<Answer> answers = new ArrayList<>();
    private final List<Call> calls = new ArrayList<>();

    /** A query containing {@code fragment} matches these transactions. */
    StubRuleSqlEvaluator matching(String fragment, List<UUID> ids) {
        return matching(fragment, ids.size(), ids);
    }

    /**
     * The same, with a true total larger than the id list - the evaluator capped what it returned.
     * The verdict must still be read from {@code matchedCount}.
     */
    StubRuleSqlEvaluator matching(String fragment, int matchedCount, List<UUID> ids) {
        answers.add(new Answer(contains(fragment), sql -> new SqlRuleResult(true, List.copyOf(ids),
                matchedCount, matchedCount > ids.size(), null, null, wrap(sql), 7L)));
        return this;
    }

    /** A query containing {@code fragment} is refused by the validator before it ever runs. */
    StubRuleSqlEvaluator rejecting(String fragment, String reason) {
        answers.add(new Answer(contains(fragment), sql ->
                new SqlRuleResult(false, List.of(), 0, false, reason, null, wrap(sql), 1L)));
        return this;
    }

    /** A query containing {@code fragment} reaches PostgreSQL and errors there. */
    StubRuleSqlEvaluator failing(String fragment, String error) {
        answers.add(new Answer(contains(fragment), sql ->
                new SqlRuleResult(false, List.of(), 0, false, null, error, wrap(sql), 3L)));
        return this;
    }

    @Override
    public SqlRuleResult evaluate(UUID customerId, String agentSql) {
        calls.add(new Call(customerId, agentSql));
        String sql = agentSql == null ? "" : agentSql;
        for (int index = answers.size() - 1; index >= 0; index--) {
            Answer answer = answers.get(index);
            if (answer.matches().test(sql)) {
                return answer.result().apply(sql);
            }
        }
        return new SqlRuleResult(true, List.of(), 0, false, null, null, wrap(sql), 5L);
    }

    /** Every query fragment this evaluator was asked to run, in order. */
    List<String> executed() {
        return calls.stream().map(Call::sql).toList();
    }

    /** The customer each query was scoped to; the tools must never widen it. */
    List<UUID> scopes() {
        return calls.stream().map(Call::customerId).toList();
    }

    int callCount() {
        return calls.size();
    }

    static String wrap(String agentSql) {
        return WRAPPER_PREFIX + agentSql + WRAPPER_SUFFIX;
    }

    private static Predicate<String> contains(String fragment) {
        return sql -> sql.contains(fragment);
    }

    private record Answer(Predicate<String> matches, Function<String, SqlRuleResult> result) {
    }

    private record Call(UUID customerId, String sql) {
    }
}
