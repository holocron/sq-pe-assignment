package com.sq.caa.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs one agent-authored rule query against PostgreSQL and reports what came back.
 *
 * <p>The defence is layered, and each layer is written here in the order it takes effect:
 *
 * <ol>
 *   <li>{@link RuleSqlValidator} refuses anything that is not a single read-only SELECT over the
 *       five customer-scoped relations;
 *   <li>{@link RuleSqlWrapper} nests the surviving fragment inside CTEs bound to this customer by
 *       JDBC parameter, and intersects whatever it returns back with that customer's transactions;
 *   <li>the statement runs on {@link ReadOnlyDataSource}, as a role whose only privilege in the
 *       database is SELECT on five single-customer views;
 *   <li>it runs in a read-only transaction, scoped by a transaction-local GUC that is itself a
 *       bound parameter, under a statement timeout set on the server and mirrored on the client;
 *   <li>the transaction is rolled back, always. Nothing this method does can be committed.
 * </ol>
 *
 * <p>It never throws. A refusal, a syntax error, a permission error and a timeout all come back as
 * {@code ok=false} carrying a sentence the model can act on, because the alternative - an exception
 * escaping into the agent loop - would turn a bad query into a failed analysis.
 */
@Service
public class PostgresRuleSqlEvaluator implements RuleSqlEvaluator {

    private static final Logger log = LoggerFactory.getLogger(PostgresRuleSqlEvaluator.class);

    /** Longest error handed back to the model; a PostgreSQL error is one useful sentence. */
    private static final int MAX_ERROR_CHARS = 400;

    /**
     * Longest quoted run left intact in an error message.
     *
     * <p>PostgreSQL quotes the offending value back at you, and that makes an error message a read
     * channel: {@code (SELECT string_agg(card.card_pan, ',') FROM card)::int = 1} answers with
     * {@code invalid input syntax for type integer: "****5663,****5663,..."}, and whatever it
     * printed would be handed to the model and written into the run's trace. Only rows this
     * customer's own analysis may read are reachable, so nothing crosses a customer boundary - but
     * the quantity is what makes a channel, so quoted runs longer than an identifier are replaced by
     * their length. {@code column "amount" does not exist} still says which column; a two-kilobyte
     * dump says only how big it was, which is all the model needs to repair the query.
     */
    private static final int MAX_QUOTED_CHARS = 40;

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"|'([^']*)'");

    /**
     * Scope of one evaluation, set transaction-locally as a bound parameter and read by every
     * {@code caa_ro} view through {@code caa_ro.current_scope()}. Unset, the views return nothing.
     */
    private static final String SCOPE_SQL =
            "SELECT set_config('caa.customer_id', ?, true), set_config('statement_timeout', ?, true)";

    /** Read back after the rule query, to prove the fragment did not move the scope under it. */
    private static final String SCOPE_CHECK_SQL = "SELECT current_setting('caa.customer_id', true)";

    private final ReadOnlyDataSource dataSource;
    private final RuleSqlProperties properties;
    private final RuleSqlValidator validator;

    public PostgresRuleSqlEvaluator(ReadOnlyDataSource dataSource, RuleSqlProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.validator = new RuleSqlValidator(properties.maxFragmentChars());
    }

    @Override
    public SqlRuleResult evaluate(UUID customerId, String agentSql) {
        long started = System.nanoTime();
        if (customerId == null) {
            return SqlRuleResult.rejected(
                    "no customer is in scope, so there is nothing to evaluate the rule against.",
                    elapsedMs(started));
        }

        RuleSqlValidator.Verdict verdict = validator.validate(agentSql);
        if (!verdict.accepted()) {
            log.debug("rule SQL refused for customer {}: {}", customerId, verdict.rejectionReason());
            return SqlRuleResult.rejected(verdict.rejectionReason(), elapsedMs(started));
        }

        String effectiveSql = RuleSqlWrapper.wrap(verdict.fragment(), properties.maxMatchedIds());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try {
                scopeToCustomer(connection, customerId);
                SqlRuleResult result = execute(connection, effectiveSql, customerId, started);
                log.debug("rule SQL matched {} transaction(s) for customer {} in {} ms",
                        result.matchedCount(), customerId, result.ms());
                return result;
            } finally {
                // Read-only either way; rolling back is what discards the transaction-local scope
                // and returns the connection to the pool carrying nothing from this evaluation.
                connection.rollback();
            }
        } catch (SQLException e) {
            log.debug("rule SQL failed for customer {}: {}", customerId, e.getMessage());
            return SqlRuleResult.failed(oneLine(e), effectiveSql, elapsedMs(started));
        } catch (RuntimeException e) {
            log.warn("rule SQL evaluation broke down for customer {}", customerId, e);
            return SqlRuleResult.failed(oneLine(e), effectiveSql, elapsedMs(started));
        }
    }

    private void scopeToCustomer(Connection connection, UUID customerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SCOPE_SQL)) {
            statement.setString(1, customerId.toString());
            statement.setString(2, properties.statementTimeoutSetting());
            statement.execute();
        }
    }

    private SqlRuleResult execute(Connection connection, String sql, UUID customerId, long started)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(properties.queryTimeoutSeconds());
            for (int parameter = 1; parameter <= RuleSqlWrapper.PARAMETERS; parameter++) {
                statement.setObject(parameter, customerId);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<UUID> matched = new ArrayList<>();
                int total = 0;
                while (rows.next()) {
                    matched.add(rows.getObject(1, UUID.class));
                    total = rows.getInt(2);
                }
                assertScopeUnchanged(connection, customerId);
                return SqlRuleResult.evaluated(
                        matched, Math.max(total, matched.size()), sql, elapsedMs(started));
            }
        }
    }

    /**
     * Confirms the customer the views were pointed at is still the customer we asked about.
     *
     * <p>A fragment that called {@code set_config} mid-statement could repoint the scope for the
     * rows evaluated after it. That already leaks nothing - the CTEs are filtered by the bound
     * parameter as well, so the result would simply come back empty - but "empty" is exactly what
     * "the rule did not fire" looks like, and turning a subverted query into a clean negative is
     * the failure mode this whole layer exists to prevent. So the scope is read back, and a query
     * that moved it is an error: the rule stays unjudged and the run fails honestly.
     */
    private static void assertScopeUnchanged(Connection connection, UUID customerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SCOPE_CHECK_SQL);
                ResultSet rows = statement.executeQuery()) {
            String scope = rows.next() ? rows.getString(1) : null;
            if (!customerId.toString().equals(scope)) {
                throw new SQLException("the query changed the customer it was scoped to (now "
                        + (scope == null || scope.isBlank() ? "unset" : scope)
                        + "); its result has been discarded and the rule is unjudged.");
            }
        }
    }

    /** One readable line for the model: no stack trace, no newlines, no data, nothing unbounded. */
    private static String oneLine(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        String collapsed = redactQuotedValues(message.replaceAll("\\s+", " ").strip());
        return collapsed.length() > MAX_ERROR_CHARS
                ? collapsed.substring(0, MAX_ERROR_CHARS) + " [...]"
                : collapsed;
    }

    /** Replaces any quoted run longer than an identifier with its length. See MAX_QUOTED_CHARS. */
    private static String redactQuotedValues(String message) {
        Matcher matcher = QUOTED.matcher(message);
        StringBuilder redacted = new StringBuilder(message.length());
        while (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String replacement = value.length() <= MAX_QUOTED_CHARS
                    ? matcher.group()
                    : "[value of " + value.length() + " characters omitted]";
            matcher.appendReplacement(redacted, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(redacted);
        return redacted.toString();
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
