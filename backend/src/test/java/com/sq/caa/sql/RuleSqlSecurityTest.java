package com.sq.caa.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.config.ReadOnlyDataSourceConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * What happens when the SQL is hostile.
 *
 * <p>This is the deliverable of the SQL safety layer, not a formality after it. Every attack below
 * is asserted twice:
 *
 * <ul>
 *   <li>through {@link RuleSqlEvaluator#evaluate}, which is what the agent can actually call, and
 *   <li><b>with the validator removed</b> - the payload is wrapped and executed directly on the
 *       read-only pool. That second assertion is the one that matters, because it is what remains
 *       true on the day somebody finds a hole in the validator's parser.
 * </ul>
 *
 * <p>Each test also leaves the database provably untouched: {@link #snapshot()} digests the users
 * table and the six activity tables before and after every single test.
 *
 * <p>The statement timeout is shortened to one second here so the denial-of-service tests do not
 * make the suite wait. The mechanism under test is identical to the shipped five seconds.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "caa.sql.statement-timeout=1s",
        // caa_readonly has CONNECTION LIMIT 8, which is a control worth keeping. Two of these test
        // contexts at a pool of three, plus a running application, exceeds it and the failures come
        // back as "connection is not available" rather than as anything about the sandbox. One
        // connection is all these tests need.
        "caa.sql.datasource.pool-size=1"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReadOnlyDataSourceConfig.class, PostgresRuleSqlEvaluator.class})
class RuleSqlSecurityTest {

    /** The application's own role. Used only to set up and to verify, never by the evaluator. */
    @Autowired
    private DataSource ownerDataSource;

    @Autowired
    private ReadOnlyDataSource readOnlyDataSource;

    @Autowired
    private RuleSqlEvaluator evaluator;

    @Autowired
    private RuleSqlProperties properties;

    /** The customer under analysis: the one with the most activity, so the queries have teeth. */
    private UUID subject;

    /** Somebody else entirely. Nothing the agent writes may ever reach a row of theirs. */
    private UUID victim;

    private UUID victimTransaction;

    private Map<String, Object> databaseBefore;

    @BeforeEach
    void pickCustomersAndSnapshotTheDatabase() throws SQLException {
        List<UUID> busiest = ownerUuids("""
                SELECT t.customer_id
                FROM transactions t
                GROUP BY t.customer_id
                ORDER BY count(*) DESC, t.customer_id
                """);
        assertTrue(busiest.size() >= 2, "the seed needs at least two customers with activity");
        subject = busiest.get(0);
        victim = busiest.get(1);
        victimTransaction = ownerUuids(
                "SELECT transaction_id FROM transactions WHERE customer_id = '" + victim
                        + "' ORDER BY transaction_id LIMIT 1").get(0);
        databaseBefore = snapshot();
    }

    @AfterEach
    void theDatabaseIsUnchanged() throws SQLException {
        assertEquals(databaseBefore, snapshot(),
                "the attack changed persistent state - every one of these must be a pure read");
    }

    // ===========================================================================================
    // Reading the users table, where the password hashes live.
    // ===========================================================================================

    @Nested
    @DisplayName("app_users and the password hashes")
    class ApplicationUsers {

        @Test
        void theEvaluatorRefusesToNameIt() {
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT transaction_id FROM app_users");
            assertRefused(result);
            assertNull(result.effectiveSql(), "nothing may reach the database once it is refused");
        }

        @Test
        void theEvaluatorRefusesItInASubqueryToo() {
            assertRefused(evaluator.evaluate(subject, """
                    SELECT tx.transaction_id FROM tx
                    WHERE (SELECT count(*) FROM app_users WHERE password_hash IS NOT NULL) > 0
                    """));
        }

        @Test
        void theRoleCannotReadItEvenWithTheValidatorRemoved() {
            assertTrue(wrappedError("SELECT transaction_id FROM public.app_users")
                            .contains("permission denied for schema public"),
                    "the read-only role must not be able to enter schema public");
            assertTrue(wrappedError("SELECT password_hash AS transaction_id FROM app_users")
                            .contains("does not exist"),
                    "with search_path = caa_ro there is no unqualified path to app_users either");
        }

        @Test
        void thePasswordHashesAreNotReachableByAnyName() {
            for (String attempt : List.of(
                    "SELECT count(*) FROM public.app_users",
                    "SELECT password_hash FROM public.app_users",
                    "SELECT * FROM app_users",
                    "SELECT * FROM caa_ro.app_users")) {
                assertNotNull(rawError(attempt), () -> "should have failed: " + attempt);
            }
        }
    }

    // ===========================================================================================
    // Reading somebody else's activity.
    // ===========================================================================================

    @Nested
    @DisplayName("another customer's transactions")
    class CrossCustomer {

        @Test
        void namingTheirCustomerIdMatchesNothing() {
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id FROM tx WHERE tx.customer_id = '" + victim + "'");
            assertTrue(result.ok(), result.rejectionReason() + " " + result.errorMessage());
            assertEquals(0, result.matchedCount());
            assertTrue(result.matchedTransactionIds().isEmpty());
        }

        @Test
        void unioningInTheirTransactionIdDropsIt() {
            SqlRuleResult result = evaluator.evaluate(subject, """
                    SELECT tx.transaction_id FROM tx
                    UNION
                    SELECT '%s'::uuid
                    """.formatted(victimTransaction));
            assertTrue(result.ok(), result.rejectionReason() + " " + result.errorMessage());
            assertFalse(result.matchedTransactionIds().contains(victimTransaction),
                    "an id the fragment invented must not survive the join back to tx");
            assertEquals(transactionCountOf(subject), result.matchedCount());
        }

        @Test
        void aSubqueryOverTheRealTableIsRefusedAndThenBlocked() {
            assertRefused(evaluator.evaluate(subject, """
                    SELECT tx.transaction_id FROM tx
                    WHERE tx.amount > (SELECT max(amount) FROM public.transactions)
                    """));
            assertTrue(wrappedError("""
                    SELECT t.transaction_id FROM public.transactions t
                    WHERE t.customer_id = '%s'
                    """.formatted(victim)).contains("permission denied for schema public"));
        }

        @Test
        void theViewsThemselvesOnlyEverShowTheCustomerInScope() throws SQLException {
            // No wrapper and no validator: this is what the read-only role sees when it selects
            // the whole table, and it is already only one customer's rows.
            List<String> throughTheView =
                    rawStrings("SELECT transaction_id FROM caa_ro.transactions ORDER BY 1");
            List<String> ownerSide = ownerUuids("""
                    SELECT transaction_id FROM transactions
                    WHERE customer_id = '%s' ORDER BY 1
                    """.formatted(subject)).stream().map(UUID::toString).toList();
            assertEquals(ownerSide, throughTheView);
            assertFalse(throughTheView.isEmpty(), "the subject must actually have activity");
        }

        @Test
        void withNoCustomerInScopeTheViewsAreEmpty() throws SQLException {
            try (Connection connection = readOnlyConnection(null);
                    Statement statement = connection.createStatement()) {
                for (String view : List.of("customers", "transactions", "card_activity",
                        "payment_activity", "crypto_activity")) {
                    try (ResultSet rows = statement.executeQuery(
                            "SELECT count(*) FROM caa_ro." + view)) {
                        rows.next();
                        assertEquals(0L, rows.getLong(1),
                                "unscoped, caa_ro." + view + " must show nothing at all");
                    }
                }
                connection.rollback();
            }
        }

        @Test
        void repointingTheScopeMidQueryLeaksNothing() {
            // The one way a fragment could argue with the views: move the setting they filter on
            // while the statement is running, so that rows read after the call belong to somebody
            // else.
            String flip = """
                    SELECT t.transaction_id FROM caa_ro.transactions t
                    WHERE set_config('caa.customer_id', '%s', true) IS NOT NULL
                    """.formatted(victim);

            assertRefused(evaluator.evaluate(subject, flip));

            // With the validator removed it still returns nothing, because the CTEs are filtered
            // by the bound customer as well as by the setting - the two are independent, and the
            // evaluator reads the setting back afterwards so a moved scope can never be reported
            // as a rule that simply did not fire.
            assertEquals(List.of(), wrappedIds(flip));
        }

        @Test
        void aCrossJoinToTheCustomerRelationStillOnlySeesOneCustomer() {
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id FROM tx, customer WHERE customer.customer_id <> '"
                            + subject + "'");
            assertTrue(result.ok(), result.rejectionReason() + " " + result.errorMessage());
            assertEquals(0, result.matchedCount(), "customer contains exactly one row: this one");
        }
    }

    // ===========================================================================================
    // Writing.
    // ===========================================================================================

    @Nested
    @DisplayName("writes and DDL")
    class Writes {

        @Test
        void theEvaluatorRefusesEveryWritingStatement() {
            for (String attempt : List.of(
                    "INSERT INTO customers (customer_id) VALUES ('" + UUID.randomUUID() + "')",
                    "UPDATE transactions SET amount = 0",
                    "DELETE FROM transactions",
                    "DROP TABLE customers",
                    "TRUNCATE app_users",
                    "ALTER TABLE customers DROP COLUMN country",
                    "GRANT SELECT ON app_users TO caa_readonly")) {
                SqlRuleResult result = evaluator.evaluate(subject, attempt);
                assertRefused(result);
            }
        }

        @Test
        void theRoleCannotWriteEvenWithTheValidatorRemoved() {
            assertNotNull(rawError("UPDATE public.transactions SET amount = 0"));
            assertNotNull(rawError("DELETE FROM public.customers"));
            assertNotNull(rawError("DROP TABLE public.risk_rules"));
            assertNotNull(rawError("CREATE TABLE public.exfiltrated (data text)"));
            assertNotNull(rawError("INSERT INTO caa_ro.transactions VALUES (DEFAULT)"));
            assertNotNull(rawError("UPDATE caa_ro.transactions SET amount = 0"));
            assertNotNull(rawError("DELETE FROM caa_ro.transactions"));
            assertNotNull(rawError("DROP VIEW caa_ro.transactions"));
            assertNotNull(rawError("CREATE TEMP TABLE staging AS SELECT 1"));
        }

        @Test
        void aDataModifyingCteCannotBeNestedInsideTheWrapper() {
            // PostgreSQL only accepts a writing CTE at the top level of a statement, and the
            // fragment is never at the top level - it is always inside rule_result.
            assertNotNull(wrappedError("""
                    WITH stolen AS (
                        DELETE FROM public.customers RETURNING customer_id AS transaction_id
                    )
                    SELECT transaction_id FROM stolen
                    """));
        }
    }

    // ===========================================================================================
    // Smuggling a second statement.
    // ===========================================================================================

    @Nested
    @DisplayName("statement chaining and comments")
    class Smuggling {

        @Test
        void aChainedStatementIsRefused() {
            assertRefused(evaluator.evaluate(subject,
                    "SELECT transaction_id FROM tx; DROP TABLE customers"));
            assertRefused(evaluator.evaluate(subject,
                    "SELECT transaction_id FROM tx WHERE tx.status = 'x'; UPDATE customers SET country = 'XX'"));
        }

        @Test
        void aChainedStatementCannotBeSentEvenWithTheValidatorRemoved() {
            String error = wrappedError("SELECT transaction_id FROM tx); DROP TABLE customers --");
            assertNotNull(error, "the driver must refuse a fragment that closes the wrapper");
        }

        @Test
        void aPayloadHiddenInALineCommentIsRefused() {
            assertRefused(evaluator.evaluate(subject,
                    "SELECT transaction_id FROM tx -- ; DELETE FROM customers"));
            assertRefused(evaluator.evaluate(subject,
                    "SELECT transaction_id /* ; DELETE FROM customers */ FROM tx"));
        }

        @Test
        void aCommentCannotDetachTheWrapperEvenWithTheValidatorRemoved() {
            // A line comment only reaches the end of its line, and the wrapper continues on the
            // next one; a block comment that is never closed takes the statement down with it.
            assertTrue(wrappedIds("SELECT transaction_id FROM tx -- everything after me is mine")
                    .size() > 0);
            assertNotNull(wrappedError("SELECT transaction_id FROM tx /* swallow the rest"));
        }
    }

    // ===========================================================================================
    // Denial of service.
    // ===========================================================================================

    @Nested
    @DisplayName("denial of service")
    class DenialOfService {

        @Test
        void sleepingIsRefused() {
            assertRefused(evaluator.evaluate(subject,
                    "SELECT transaction_id FROM tx WHERE pg_sleep(30) IS NULL"));
        }

        @Test
        void sleepingIsCancelledByTheServerEvenWithTheValidatorRemoved() {
            long started = System.nanoTime();
            String error = rawError("SELECT pg_sleep(30)");
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertNotNull(error);
            assertTrue(error.contains("canceling statement"), error);
            assertTrue(elapsedMs < 10_000, "the timeout must fire long before the sleep ends, was "
                    + elapsedMs + " ms");
        }

        @Test
        void adeliberatelyExpensiveQueryHitsTheStatementTimeout() {
            SqlRuleResult result = evaluator.evaluate(subject, """
                    SELECT a.transaction_id
                    FROM tx a, tx b, tx c, tx d, tx e, tx f, tx g
                    WHERE a.amount + b.amount + c.amount + d.amount + e.amount + f.amount
                          + g.amount > 0
                    """);
            assertFalse(result.ok(), "a query that never finishes is not an answer");
            assertNotNull(result.errorMessage());
            assertTrue(result.errorMessage().contains("canceling statement"), result.errorMessage());
            assertTrue(result.ms() < 10_000, "cancelled after " + result.ms() + " ms");
            assertEquals(0, result.matchedCount());
        }
    }

    // ===========================================================================================
    // Catalog enumeration.
    // ===========================================================================================

    @Nested
    @DisplayName("catalog enumeration")
    class Enumeration {

        @Test
        void theEvaluatorRefusesToLookAtTheCatalog() {
            assertRefused(evaluator.evaluate(subject,
                    "SELECT transaction_id FROM information_schema.tables"));
            assertRefused(evaluator.evaluate(subject,
                    "SELECT transaction_id FROM pg_catalog.pg_tables"));
            assertRefused(evaluator.evaluate(subject,
                    "SELECT transaction_id, current_setting('caa.customer_id') FROM tx"));
        }

        @Test
        void informationSchemaShowsNothingOfTheApplicationEvenWithTheValidatorRemoved()
                throws SQLException {
            List<String> visible = rawStrings("""
                    SELECT table_schema || '.' || table_name
                    FROM information_schema.tables
                    WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
                    ORDER BY 1
                    """);
            assertEquals(List.of(
                    "caa_ro.card_activity",
                    "caa_ro.crypto_activity",
                    "caa_ro.customers",
                    "caa_ro.payment_activity",
                    "caa_ro.transactions"),
                    visible.stream().sorted().toList(),
                    "the role may see the five scoped views and nothing else");
        }

        @Test
        void theRoleCannotReadTheRolePasswords() {
            assertNotNull(rawError("SELECT rolpassword FROM pg_catalog.pg_authid"));
        }

        @Test
        void theRoleCannotReadTheOtherApplicationTables() {
            for (String table : List.of("analysis_runs", "risk_rules", "risk_assessments",
                    "knowledge_documents", "document_chunks", "flyway_schema_history")) {
                assertNotNull(rawError("SELECT * FROM public." + table),
                        () -> "public." + table + " must be unreachable");
            }
        }
    }

    // ===========================================================================================
    // The queries this whole layer exists to run.
    // ===========================================================================================

    @Nested
    @DisplayName("the happy path")
    class HappyPath {

        @Test
        void aThresholdQueryReturnsExactlyTheRightTransactions() throws SQLException {
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id FROM tx WHERE tx.amount >= 5000");

            assertTrue(result.ok(), result.rejectionReason() + " " + result.errorMessage());
            List<UUID> expected = ownerUuids("""
                    SELECT transaction_id FROM transactions
                    WHERE customer_id = '%s' AND amount >= 5000
                    """.formatted(subject));
            assertEquals(expected.size(), result.matchedCount());
            assertEquals(expected.stream().sorted().toList(),
                    result.matchedTransactionIds().stream().sorted().toList());
            assertFalse(result.capped());
            assertNull(result.rejectionReason());
            assertNull(result.errorMessage());
        }

        @Test
        void theCountingRuleTheModelGotWrongIsNowPostgresArithmetic() throws SQLException {
            // "eight or more transactions in any rolling 24-hour window" - the condition a live run
            // cleared by reasoning that eight was "below the required minimum of 10".
            String window = """
                    SELECT t.transaction_id
                    FROM tx t
                    WHERE (SELECT count(*) FROM tx w
                           WHERE w.created_at > t.created_at - INTERVAL '24 hours'
                             AND w.created_at <= t.created_at) >= 8
                    """;
            SqlRuleResult result = evaluator.evaluate(subject, window);
            assertTrue(result.ok(), result.rejectionReason() + " " + result.errorMessage());

            List<UUID> expected = ownerUuids("""
                    SELECT t.transaction_id
                    FROM transactions t
                    WHERE t.customer_id = '%s'
                      AND (SELECT count(*) FROM transactions w
                           WHERE w.customer_id = t.customer_id
                             AND w.created_at > t.created_at - INTERVAL '24 hours'
                             AND w.created_at <= t.created_at) >= 8
                    """.formatted(subject));
            assertEquals(expected.stream().sorted().toList(),
                    result.matchedTransactionIds().stream().sorted().toList());
            assertEquals(expected.size(), result.matchedCount());
        }

        @Test
        void everyColumnTheValidatorAdvertisesReallyExists() {
            String projection = RuleSqlSchema.relationNames().stream()
                    .flatMap(relation -> RuleSqlSchema.columnsOf(relation).stream()
                            .map(column -> relation + "." + column + " AS "
                                    + relation + "_" + column))
                    .collect(Collectors.joining(",\n       "));
            SqlRuleResult result = evaluator.evaluate(subject, """
                    SELECT tx.transaction_id,
                           %s
                    FROM tx
                    CROSS JOIN customer
                    LEFT JOIN card ON card.transaction_id = tx.transaction_id
                    LEFT JOIN payment ON payment.transaction_id = tx.transaction_id
                    LEFT JOIN crypto ON crypto.transaction_id = tx.transaction_id
                    """.formatted(projection));
            assertTrue(result.ok(), "RuleSqlSchema has drifted from the wrapper: "
                    + result.rejectionReason() + " " + result.errorMessage());
            assertEquals(transactionCountOf(subject), result.matchedCount());
        }

        @Test
        void aRuleThatDoesNotFireIsAnAnswerNotAFailure() {
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id FROM tx WHERE tx.amount > 100000000");
            assertTrue(result.ok(), "an empty result is a verdict: the rule did not trigger");
            assertEquals(0, result.matchedCount());
            assertNull(result.errorMessage());
        }

        @Test
        void theEffectiveSqlIsRecordedForTheAuditTrail() {
            String fragment = "SELECT tx.transaction_id FROM tx WHERE tx.amount >= 5000";
            SqlRuleResult result = evaluator.evaluate(subject, fragment);
            assertNotNull(result.effectiveSql());
            assertTrue(result.effectiveSql().startsWith("WITH customer AS MATERIALIZED"));
            assertTrue(result.effectiveSql().contains(fragment));
            assertTrue(result.effectiveSql().contains("caa_ro.transactions"));
            assertTrue(result.effectiveSql().contains("LIMIT " + properties.maxMatchedIds()));
        }

        @Test
        void theCountIsTheTrueTotalEvenWhenTheIdListIsCapped() {
            RuleSqlProperties capped = new RuleSqlProperties(
                    properties.datasource(), properties.statementTimeout(), 2,
                    properties.maxFragmentChars());
            RuleSqlEvaluator narrow = new PostgresRuleSqlEvaluator(readOnlyDataSource, capped);

            SqlRuleResult result = narrow.evaluate(subject, "SELECT tx.transaction_id FROM tx");
            assertTrue(result.ok(), result.rejectionReason() + " " + result.errorMessage());
            assertEquals(2, result.matchedTransactionIds().size());
            assertTrue(result.capped());
            assertEquals(transactionCountOf(subject), result.matchedCount(),
                    "capping the id list must never cap the number the verdict is derived from");
        }
    }

    // ===========================================================================================
    // The coverage guarantee: an unanswered rule must never look like an answered one.
    // ===========================================================================================

    @Nested
    @DisplayName("a rule that could not be judged")
    class Unjudged {

        @Test
        void aRefusedFragmentIsNotTriggeredAndNotJudged() {
            SqlRuleResult result = evaluator.evaluate(subject, "SELECT transaction_id FROM app_users");
            assertFalse(result.ok());
            assertNotNull(result.rejectionReason());
            assertNull(result.errorMessage());
            assertEquals(0, result.matchedCount());
            assertTrue(result.matchedTransactionIds().isEmpty());
        }

        @Test
        void aBrokenQueryComesBackAsAnErrorNotAsZeroMatches() {
            // Valid to the validator, meaningless to PostgreSQL: an amount is not a transaction id.
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT tx.amount AS transaction_id FROM tx");
            assertFalse(result.ok(), "a type error must not be reported as 'the rule did not fire'");
            assertNotNull(result.errorMessage());
            assertNotNull(result.effectiveSql(), "the attempt still belongs in the audit trail");
        }

        @Test
        void badInputNeverThrows() {
            assertRefused(evaluator.evaluate(subject, null));
            assertRefused(evaluator.evaluate(subject, ""));
            assertRefused(evaluator.evaluate(subject, "not sql at all"));
            assertRefused(evaluator.evaluate(null, "SELECT tx.transaction_id FROM tx"));
        }

        @Test
        void everyOutcomeCarriesItsElapsedTime() {
            assertTrue(evaluator.evaluate(subject, "SELECT tx.transaction_id FROM tx").ms() >= 0);
            assertTrue(evaluator.evaluate(subject, "DROP TABLE customers").ms() >= 0);
        }
    }

    // ===========================================================================================
    // Helpers.
    // ===========================================================================================

    private static void assertRefused(SqlRuleResult result) {
        assertFalse(result.ok(), "should have been refused");
        assertNotNull(result.rejectionReason(), "a refusal has to tell the model why");
        assertEquals(0, result.matchedCount());
        assertTrue(result.matchedTransactionIds().isEmpty());
    }

    /**
     * Runs a fragment through the wrapper with the validator bypassed - the second assertion every
     * attack in this class gets, and the one that says what the database itself enforces.
     */
    private List<UUID> runWrapped(String fragment) throws SQLException {
        try (Connection connection = readOnlyConnection(subject);
                PreparedStatement statement = connection.prepareStatement(
                        RuleSqlWrapper.wrap(fragment, properties.maxMatchedIds()))) {
            for (int parameter = 1; parameter <= RuleSqlWrapper.PARAMETERS; parameter++) {
                statement.setObject(parameter, subject);
            }
            List<UUID> ids = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getObject(1, UUID.class));
                }
            }
            connection.rollback();
            return ids;
        }
    }

    private List<UUID> wrappedIds(String fragment) {
        try {
            return runWrapped(fragment);
        } catch (SQLException e) {
            throw new AssertionError("expected this to run: " + e.getMessage(), e);
        }
    }

    /** Same, but expecting failure: the PostgreSQL error, or a failed test if there was none. */
    private String wrappedError(String fragment) {
        try {
            List<UUID> ids = runWrapped(fragment);
            throw new AssertionError("expected a database error, got " + ids.size() + " row(s)");
        } catch (SQLException e) {
            return e.getMessage();
        }
    }

    /** Runs arbitrary SQL on the read-only pool; returns the error, or null when it succeeded. */
    private String rawError(String sql) {
        try (Connection connection = readOnlyConnection(subject);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.rollback();
            return null;
        } catch (SQLException e) {
            return e.getMessage();
        }
    }

    private List<String> rawStrings(String sql) throws SQLException {
        try (Connection connection = readOnlyConnection(subject);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    /** A connection set up exactly the way the evaluator sets one up. */
    private Connection readOnlyConnection(UUID scope) throws SQLException {
        Connection connection = readOnlyDataSource.getConnection();
        connection.setAutoCommit(false);
        connection.setReadOnly(true);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('caa.customer_id', ?, true),"
                        + " set_config('statement_timeout', ?, true)")) {
            statement.setString(1, scope == null ? "" : scope.toString());
            statement.setString(2, properties.statementTimeoutSetting());
            statement.execute();
        }
        return connection;
    }

    private int transactionCountOf(UUID customer) {
        try (Connection connection = ownerDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM transactions WHERE customer_id = ?")) {
            statement.setObject(1, customer);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    private List<UUID> ownerUuids(String sql) throws SQLException {
        try (Connection connection = ownerDataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            List<UUID> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(rows.getObject(1, UUID.class));
            }
            return ids;
        }
    }

    /**
     * Everything an attack in this class could plausibly damage, read with the owner role.
     *
     * <p>{@code analysis_runs}, {@code risk_assessments} and {@code risk_rules} are left out on
     * purpose: the application writes to them legitimately, and nothing here targets them beyond
     * checking that the read-only role cannot see them at all.
     */
    private Map<String, Object> snapshot() throws SQLException {
        Map<String, Object> state = new LinkedHashMap<>();
        try (Connection connection = ownerDataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String table : List.of("app_users", "customers", "transactions", "card_activity",
                    "payment_activity", "crypto_activity")) {
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    rows.next();
                    state.put(table + ".count", rows.getLong(1));
                }
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT md5(string_agg(username || password_hash || role, '|' ORDER BY username))"
                            + " FROM app_users")) {
                rows.next();
                state.put("app_users.digest", rows.getString(1));
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT coalesce(sum(amount), 0)::text FROM transactions")) {
                rows.next();
                state.put("transactions.amount_sum", rows.getString(1));
            }
        }
        return state;
    }
}
