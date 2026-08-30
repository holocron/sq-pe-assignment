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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * An independent penetration test of the SQL sandbox, written against the source rather than
 * against the author's own threat model.
 *
 * <p>{@code RuleSqlSecurityTest} proves the attacks its author thought of. This class exists to
 * attack the layers themselves, and in particular to answer one question the design rests on:
 * <b>is the customer scope guaranteed by construction, or only by the validator?</b> Every test
 * here therefore asserts the outcome that must hold whichever ring stopped the payload, using
 * {@link #assertNothingEscaped}: either the fragment never ran, or it ran and returned nothing but
 * transactions that already belong to the customer under analysis. An assertion phrased that way
 * survives the validator being hardened <em>and</em> the validator being holed, which is the only
 * useful way to assert a defence in depth.
 *
 * <p>Where an attack is stopped by a ring other than the one a reader would expect, the test says
 * so in its name and its comment. {@code AliasLaundering} is the group that found a real hole: four
 * payloads were once refused by the database grants alone while {@link RuleSqlValidator} accepted
 * them, which is the correct outcome produced by the wrong ring. Those tests now assert the
 * validator refuses them itself, and one of them uses a schema that appears in no list at all, so
 * the assertion is about the shape of the name rather than about a word. One whole class of attack
 * is still not stopped, because it is not an attack on the sandbox: see {@code AdversarialRuleText}.
 *
 * <p>The statement timeout is one second here so the denial-of-service tests do not slow the suite;
 * the mechanism is identical to the shipped five.
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
class SqlSandboxAttackTest {

    /**
     * The seeded bcrypt hash of the {@code admin} login. If any attack in this class ever puts these
     * characters anywhere - in a result, in an error message, in the database after the fact - the
     * sandbox has failed at its only real job.
     */
    private static final String ADMIN_HASH =
            "$2a$10$8K1p/a0dL1LXMIgoEDFrwOEQgNkEGjeKGfRG.dDp6XGmStgezLRtq";

    /** The bcrypt prefix every seeded hash starts with; cheaper to search for than a whole hash. */
    private static final String HASH_MARKER = "$2a$10$";

    @Autowired
    private DataSource ownerDataSource;

    @Autowired
    private ReadOnlyDataSource readOnlyDataSource;

    @Autowired
    private RuleSqlEvaluator evaluator;

    @Autowired
    private RuleSqlProperties properties;

    /** The customer under analysis. */
    private UUID subject;

    /** A customer the agent is not analysing. Not one row of theirs may ever come back. */
    private UUID victim;

    private UUID victimTransaction;

    /** Every transaction the subject legitimately owns: the only ids any attack may produce. */
    private Set<UUID> subjectTransactions;

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
        subjectTransactions = new LinkedHashSet<>(ownerUuids(
                "SELECT transaction_id FROM transactions WHERE customer_id = '" + subject + "'"));
        victimTransaction = ownerUuids("SELECT transaction_id FROM transactions WHERE customer_id = '"
                + victim + "' ORDER BY transaction_id LIMIT 1").get(0);
        databaseBefore = snapshot();
    }

    @AfterEach
    void theDatabaseIsUntouchedAndThePasswordHashesAreIntact() throws SQLException {
        Map<String, Object> after = snapshot();
        assertEquals(databaseBefore, after, "an attack changed persistent state");
        assertEquals(ADMIN_HASH, after.get("app_users.admin_hash"),
                "the admin password hash is not what it was before the attack");
    }

    // ===========================================================================================
    // 1. app_users, and the password hashes in it.
    // ===========================================================================================

    @Nested
    @DisplayName("reading app_users by any route")
    class PasswordHashes {

        @Test
        void directlyFromTheTable() {
            assertNothingEscaped("SELECT transaction_id FROM app_users");
        }

        @Test
        void fromAScalarSubqueryInThePredicate() {
            assertNothingEscaped("""
                    SELECT tx.transaction_id FROM tx
                    WHERE (SELECT max(u.password_hash) FROM app_users u) > ''
                    """);
        }

        @Test
        void unionedIntoTheResult() {
            assertNothingEscaped(
                    "SELECT tx.transaction_id FROM tx UNION SELECT u.id FROM app_users u");
        }

        @Test
        void hiddenInTheFragmentsOwnCte() {
            assertNothingEscaped("""
                    WITH stolen AS (SELECT u.username, u.password_hash FROM app_users u)
                    SELECT tx.transaction_id FROM tx, stolen
                    """);
        }

        @Test
        void joinedOntoTheCustomersTransactions() {
            assertNothingEscaped("""
                    SELECT tx.transaction_id FROM tx
                    LEFT JOIN app_users u ON u.username = tx.currency
                    """);
        }

        @Test
        void throughAFunctionCall() {
            assertNothingEscaped(
                    "SELECT tx.transaction_id FROM tx WHERE query_to_xml('SELECT password_hash "
                            + "FROM app_users', true, true, '') IS NOT NULL");
        }

        @Test
        void throughACastThatWouldPrintTheValueInTheErrorMessage() {
            SqlRuleResult result = assertNothingEscaped("""
                    SELECT tx.transaction_id FROM tx
                    WHERE (SELECT string_agg(u.password_hash, ',') FROM app_users u)::int = 1
                    """);
            assertNoHashAnywhere(result);
        }

        @Test
        void throughASetReturningFunction() {
            assertNothingEscaped(
                    "SELECT tx.transaction_id FROM tx, json_to_recordset('[]') AS app_users");
        }

        @Test
        void andTheRoleCannotReachItEvenWithTheValidatorTakenOutOfTheWay() {
            for (String fragment : List.of(
                    "SELECT u.id AS transaction_id FROM public.app_users u",
                    "SELECT u.id AS transaction_id FROM app_users u",
                    "SELECT tx.transaction_id FROM tx WHERE EXISTS "
                            + "(SELECT 1 FROM public.app_users)",
                    "SELECT tx.transaction_id FROM tx, LATERAL "
                            + "(SELECT u.password_hash FROM public.app_users u LIMIT 1) leak")) {
                Raw raw = unvalidated(fragment);
                assertNotNull(raw.error(), "this must not run at all: " + fragment);
                assertTrue(raw.error().contains("permission denied for schema public")
                                || raw.error().contains("does not exist"),
                        "expected a privilege or resolution failure, got: " + raw.error());
                assertFalse(raw.error().contains(HASH_MARKER), "the error leaked a hash");
            }
        }
    }

    // ===========================================================================================
    // 2. Another customer's transactions.
    // ===========================================================================================

    @Nested
    @DisplayName("reading another customer")
    class CrossCustomer {

        @Test
        void namingTheirCustomerIdLiterally() {
            SqlRuleResult result = assertNothingEscaped(
                    "SELECT tx.transaction_id FROM tx WHERE tx.customer_id = '" + victim + "'");
            assertTrue(result.ok(), "a legal query over the customer's own relations should run");
            assertEquals(0, result.matchedCount(), "there is no route to another customer's rows");
        }

        @Test
        void namingTheirTransactionIdLiterallyInAUnionAll() {
            SqlRuleResult result = assertNothingEscaped(
                    "SELECT tx.transaction_id FROM tx WHERE 1 = 0 "
                            + "UNION ALL SELECT '" + victimTransaction + "'::uuid");
            assertTrue(result.ok());
            assertEquals(0, result.matchedCount(),
                    "an id the fragment invented is dropped by the wrapper's join back to tx");
        }

        @Test
        void aCorrelatedSubqueryOverTheRealTable() {
            assertNothingEscaped("""
                    SELECT tx.transaction_id FROM tx
                    WHERE EXISTS (SELECT 1 FROM public.transactions o
                                  WHERE o.customer_id <> tx.customer_id)
                    """);
        }

        @Test
        void aSelfJoinThatTriesToLeaveTheCteScope() {
            SqlRuleResult result = assertNothingEscaped("""
                    SELECT a.transaction_id FROM tx a
                    JOIN tx b ON b.customer_id <> a.customer_id
                    """);
            assertTrue(result.ok());
            assertEquals(0, result.matchedCount(), "tx contains exactly one customer, so this is empty");
        }

        @Test
        void aLateralJoin() {
            assertNothingEscaped("""
                    SELECT tx.transaction_id FROM tx,
                    LATERAL (SELECT o.transaction_id FROM public.transactions o
                             WHERE o.customer_id <> tx.customer_id LIMIT 1) other
                    """);
        }

        @Test
        void theWrapperDropsForeignIdsEvenWhenTheFragmentShadowsTx() {
            Raw raw = unvalidated(
                    "WITH tx AS (SELECT '" + victimTransaction + "'::uuid AS transaction_id) "
                            + "SELECT tx.transaction_id FROM tx");
            assertNull(raw.error(), "this is legal SQL and is meant to run");
            assertTrue(raw.ids().isEmpty(),
                    "a fragment that redefines tx inside itself cannot redefine the tx the wrapper "
                            + "joins back to - that one is bound to the customer by JDBC parameter");
        }

        @Test
        void theWrapperDropsForeignIdsWithTheValidatorTakenOutOfTheWay() {
            Raw raw = unvalidated("SELECT '" + victimTransaction + "'::uuid AS transaction_id");
            assertNull(raw.error());
            assertTrue(raw.ids().isEmpty(), "the intersection with tx is what makes the scope a "
                    + "construction guarantee rather than a validation guarantee");
        }

        @Test
        void everySurvivingIdBelongsToTheSubjectNoMatterWhatTheFragmentAsksFor() {
            for (String fragment : List.of(
                    "SELECT tx.transaction_id FROM tx",
                    "SELECT tx.transaction_id FROM tx UNION ALL SELECT '" + victimTransaction
                            + "'::uuid",
                    "SELECT c.customer_id AS transaction_id FROM customer c")) {
                Raw raw = unvalidated(fragment);
                assertNull(raw.error(), fragment);
                assertTrue(subjectTransactions.containsAll(raw.ids()),
                        "an id that is not the subject's escaped: " + fragment);
            }
        }
    }

    // ===========================================================================================
    // 3. Writes.
    // ===========================================================================================

    @Nested
    @DisplayName("writing anything at all")
    class Writes {

        @Test
        void everyWritingStatementIsRefusedByTheEvaluator() {
            for (String attempt : List.of(
                    "INSERT INTO app_users (username) VALUES ('mallory') RETURNING id AS transaction_id",
                    "UPDATE transactions SET amount = 0 RETURNING transaction_id",
                    "DELETE FROM transactions RETURNING transaction_id",
                    "DROP TABLE app_users",
                    "ALTER TABLE transactions DROP COLUMN amount",
                    "TRUNCATE app_users",
                    "SELECT tx.transaction_id FROM tx UNION ALL DELETE FROM app_users")) {
                SqlRuleResult result = evaluator.evaluate(subject, attempt);
                assertFalse(result.ok(), "should have been refused: " + attempt);
                assertNotNull(result.rejectionReason(), "the validator should name the word: " + attempt);
                assertNull(result.effectiveSql(), "nothing may reach the database: " + attempt);
            }
        }

        @Test
        void aWritableCteIsRefused() {
            SqlRuleResult result = evaluator.evaluate(subject, """
                    WITH stolen AS (
                        INSERT INTO app_users (username, password_hash, role)
                        VALUES ('mallory', 'x', 'ADMIN') RETURNING id
                    )
                    SELECT tx.transaction_id FROM tx, stolen
                    """);
            assertFalse(result.ok());
            assertNotNull(result.rejectionReason());
        }

        @Test
        void theRoleCannotWriteEvenWithTheValidatorTakenOutOfTheWay() {
            for (String statement : List.of(
                    "INSERT INTO public.app_users (username) VALUES ('mallory')",
                    "UPDATE public.transactions SET amount = 0",
                    "DELETE FROM public.transactions",
                    "DROP TABLE public.app_users",
                    "ALTER TABLE public.transactions DROP COLUMN amount",
                    "TRUNCATE public.app_users",
                    "CREATE TEMP TABLE loot AS SELECT 1",
                    "GRANT SELECT ON public.app_users TO caa_readonly",
                    "UPDATE caa_ro.transactions SET amount = 0")) {
                String error = rawError(statement);
                assertNotNull(error, "this must not succeed: " + statement);
            }
        }

        @Test
        void aWritableCteCannotBeNestedInsideTheWrapperEither() {
            Raw raw = unvalidated("""
                    WITH stolen AS (
                        INSERT INTO public.app_users (username, password_hash, role)
                        VALUES ('mallory', 'x', 'ADMIN') RETURNING id
                    )
                    SELECT tx.transaction_id FROM tx, stolen
                    """);
            assertNotNull(raw.error(), "a data-modifying CTE must not run inside the wrapper");
        }
    }

    // ===========================================================================================
    // 4. Statement chaining.
    // ===========================================================================================

    @Nested
    @DisplayName("chaining a second statement")
    class Chaining {

        @Test
        void aPlainSemicolonIsRefused() {
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id FROM tx; DROP TABLE app_users");
            assertFalse(result.ok());
            assertTrue(result.rejectionReason().contains("';'"), result.rejectionReason());
        }

        @Test
        void aTrailingSemicolonIsStrippedRatherThanTreatedAsAnAttack() {
            SqlRuleResult result = evaluator.evaluate(subject, "SELECT tx.transaction_id FROM tx;");
            assertTrue(result.ok(), "one trailing ';' is a habit, not an injection");
        }

        @Test
        void aSemicolonInsideAStringLiteralStaysDataAndCannotChain() {
            SqlRuleResult result = assertNothingEscaped(
                    "SELECT tx.transaction_id FROM tx WHERE tx.currency = "
                            + "'USD''; DROP TABLE app_users; SELECT '''");
            assertTrue(result.ok(), "a ';' inside a literal is a character, not a statement break");
            assertEquals(0, result.matchedCount());
        }

        @Test
        void aDollarQuotedBlockIsRefusedOutright() {
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id FROM tx WHERE tx.currency = $tag$USD$tag$");
            assertFalse(result.ok());
            assertTrue(result.rejectionReason().contains("'$'"), result.rejectionReason());
        }

        @Test
        void aDollarQuotedFunctionBodyIsRefusedOutright() {
            assertNothingEscaped("""
                    SELECT tx.transaction_id FROM tx
                    WHERE $$ SELECT password_hash FROM app_users $$ IS NOT NULL
                    """);
        }

        @Test
        void theDriverItselfRefusesAChainedStatementWithTheValidatorTakenOutOfTheWay() {
            assertNotNull(rawError("SELECT 1; DROP TABLE public.app_users"),
                    "a chained DDL must not succeed on the read-only pool");
        }
    }

    // ===========================================================================================
    // 5. Comments and whitespace.
    // ===========================================================================================

    @Nested
    @DisplayName("smuggling through comments and exotic whitespace")
    class Smuggling {

        @Test
        void lineCommentsAreRefused() {
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id FROM tx -- WHERE tx.amount > 0\n");
            assertFalse(result.ok());
            assertTrue(result.rejectionReason().contains("'--'"), result.rejectionReason());
        }

        @Test
        void blockCommentsAreRefused() {
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id /* everything after here is invisible */ FROM tx");
            assertFalse(result.ok());
            assertTrue(result.rejectionReason().contains("'/*'"), result.rejectionReason());
        }

        @Test
        void nestedBlockCommentsAreRefused() {
            assertNothingEscaped(
                    "SELECT tx.transaction_id FROM tx /* outer /* inner */ still commented */");
        }

        @Test
        void anUnbalancedCommentOpenerIsRefused() {
            assertNothingEscaped("SELECT tx.transaction_id FROM tx /* never closed");
        }

        @Test
        void aCommentCannotDetachTheRestOfTheWrapper() {
            Raw raw = unvalidated("SELECT tx.transaction_id FROM tx --");
            assertNull(raw.error(), "a line comment only comments out the newline that follows it");
            assertTrue(subjectTransactions.containsAll(raw.ids()));
            assertNotNull(rawError("SELECT tx.transaction_id FROM tx /*"),
                    "an unterminated block comment kills the statement rather than truncating it");
        }

        @Test
        void aNonBreakingSpaceIsRefusedRatherThanSilentlyEaten() {
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT\u00a0tx.transaction_id FROM tx");
            assertFalse(result.ok(), "U+00A0 is not whitespace and must not be treated as an operator");
        }

        @Test
        void aZeroWidthSpaceIsRefused() {
            assertNothingEscaped("SELECT tx.transaction_id\u200b FROM tx");
        }

        @Test
        void anEmSpaceIsRefusedByTheValidatorRatherThanLexedDifferently() {
            // The two lexers used to disagree here: Character.isWhitespace(U+2003) is true, so the
            // validator treated it as a token separator, while PostgreSQL's scanner treats every
            // byte above 0x7F as an identifier character and read "SELECT<em space>tx" as one name.
            // It failed closed only because no reachable object has such a name. Two lexers that
            // disagree about where a token ends is not a property to rely on, so every character
            // outside printable ASCII is now refused before tokenising.
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT\u2003tx.transaction_id FROM tx");
            assertFalse(result.ok(), "it must not run");
            assertNotNull(result.rejectionReason(), "the validator is the layer that refuses it");
            assertTrue(result.rejectionReason().contains("U+2003"), result.rejectionReason());
            assertNull(result.effectiveSql(), "nothing reached the parser");
            assertEquals(0, result.matchedCount());
        }

        @Test
        void aVerticalTabIsWhitespaceToBothLexersAndChangesNothing() {
            SqlRuleResult result = evaluator.evaluate(subject, "SELECT\013tx.transaction_id FROM tx");
            assertTrue(result.ok());
            assertTrue(subjectTransactions.containsAll(result.matchedTransactionIds()));
        }

        @Test
        void quotedIdentifiersHaveNoAcceptedForm() {
            assertNothingEscaped("SELECT tx.transaction_id FROM \"public\".\"app_users\"");
        }
    }

    // ===========================================================================================
    // 6. Catalog enumeration.
    // ===========================================================================================

    @Nested
    @DisplayName("enumerating the catalog")
    class Enumeration {

        @Test
        void everyCatalogRouteIsRefusedByName() {
            for (String fragment : List.of(
                    "SELECT c.oid AS transaction_id FROM pg_catalog.pg_class c",
                    "SELECT t.table_name AS transaction_id FROM information_schema.tables t",
                    "SELECT tx.transaction_id FROM tx WHERE version() IS NOT NULL",
                    "SELECT tx.transaction_id FROM tx WHERE current_setting('caa.customer_id') <> ''",
                    "SELECT tx.transaction_id FROM tx WHERE current_user <> ''",
                    "SELECT tx.transaction_id FROM tx WHERE 'app_users'::regclass IS NOT NULL",
                    "SELECT tx.transaction_id FROM tx WHERE to_regclass('app_users') IS NOT NULL",
                    "SELECT tx.transaction_id FROM tx WHERE has_table_privilege('app_users', 'SELECT')",
                    "SELECT a.transaction_id FROM pg_stat_activity a")) {
                SqlRuleResult result = evaluator.evaluate(subject, fragment);
                assertFalse(result.ok(), "should have been refused: " + fragment);
                assertNotNull(result.rejectionReason(), "the validator should refuse: " + fragment);
            }
        }

        @Test
        void theCatalogCannotBeLaunderedThroughADeclaredAlias() {
            // checkForbiddenWords runs over the raw tokens before any alias is collected, so
            // declaring "information_schema" or "pg_catalog" as an alias does not launder it.
            assertNothingEscaped("""
                    SELECT tx.transaction_id, tx.amount AS information_schema, tx.currency AS tables
                    FROM tx, information_schema.tables
                    """);
            assertNothingEscaped("""
                    SELECT tx.transaction_id, tx.amount AS pg_catalog, tx.currency AS pg_class
                    FROM tx, pg_catalog.pg_class
                    """);
        }

        @Test
        void theCatalogShowsNoApplicationDataToTheReadOnlyRoleAnyway() throws SQLException {
            List<String> visible = rawStrings(
                    "SELECT table_schema || '.' || table_name FROM information_schema.tables "
                            + "WHERE table_schema NOT IN ('pg_catalog', 'information_schema') "
                            + "ORDER BY 1");
            assertEquals(List.of("caa_ro.card_activity", "caa_ro.crypto_activity",
                    "caa_ro.customers", "caa_ro.payment_activity", "caa_ro.transactions"), visible,
                    "the read-only role must not even be able to see that the app tables exist");
        }

        @Test
        void theRolePasswordsAreNotInAnyCatalogTheRoleCanRead() {
            assertNotNull(rawError("SELECT rolpassword FROM pg_authid"),
                    "pg_authid must stay closed");
            assertNotNull(rawError("SELECT passwd FROM pg_shadow"), "pg_shadow must stay closed");
        }

        @Test
        void theApplicationsOwnTablesAreUnreachableByEveryName() {
            for (String table : List.of("app_users", "analysis_runs", "risk_rules",
                    "risk_assessments", "knowledge_documents", "document_chunks",
                    "flyway_schema_history")) {
                assertNotNull(rawError("SELECT 1 FROM public." + table), "public." + table);
                assertNotNull(rawError("SELECT 1 FROM " + table), table);
                assertNotNull(rawError("SELECT 1 FROM caa_ro." + table), "caa_ro." + table);
            }
        }
    }

    // ===========================================================================================
    // 7. Denial of service.
    // ===========================================================================================

    @Nested
    @DisplayName("denial of service")
    class DenialOfService {

        @Test
        void sleepingIsRefusedByNameAndCancelledByTheServer() {
            SqlRuleResult refused = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id FROM tx WHERE pg_sleep(30) IS NULL");
            assertFalse(refused.ok());
            assertNotNull(refused.rejectionReason());

            long started = System.currentTimeMillis();
            assertNotNull(rawError("SELECT pg_sleep(30)"), "the server must cancel it");
            assertTrue(System.currentTimeMillis() - started < 10_000,
                    "statement_timeout must cut it short, not let it run for thirty seconds");
        }

        @Test
        void aCartesianProductIsCancelled() {
            long started = System.currentTimeMillis();
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT a.transaction_id FROM tx a, tx b, tx c, tx d, tx e, tx f, tx g");
            assertFalse(result.ok(), "a query this expensive must not be allowed to finish");
            assertNotNull(result.errorMessage());
            // Whichever bound the role hits first is fine; what matters is that one of them is
            // reached long before the query could finish. Since temp_file_limit was added this is
            // usually the disk bound rather than the clock, because a seven-way product spills
            // before it has burned five seconds.
            assertTrue(bounded(result.errorMessage()), result.errorMessage());
            assertEquals(0, result.matchedCount(), "a cancelled query is unjudged, not 'not triggered'");
            assertTrue(System.currentTimeMillis() - started < 15_000, "a bound must apply");
        }

        /** Whether a PostgreSQL error is one of the resource bounds set on caa_readonly. */
        private static boolean bounded(String error) {
            return error.contains("timeout") || error.contains("temp_file_limit")
                    || error.contains("out of memory") || error.contains("memory alloc");
        }

        @Test
        void generateSeriesWithAHugeBoundIsRefusedAndOtherwiseCancelled() {
            SqlRuleResult refused = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id FROM tx, generate_series(1, 1000000000) g");
            assertFalse(refused.ok());
            assertNotNull(refused.rejectionReason());

            Raw raw = unvalidated(
                    "SELECT tx.transaction_id FROM tx, generate_series(1, 1000000000) g");
            assertNotNull(raw.error(), "with the validator gone the server has to be the bound");
            assertTrue(bounded(raw.error()), raw.error());
        }

        @Test
        void anUnboundedRecursiveCteNeverReachesTheDatabase() {
            // This used to reach PostgreSQL: "recursive" was not a keyword the validator knew, and
            // declaring it as a column alias put it on the allow-list, so the statement timeout was
            // the only thing that ended it. RECURSIVE is now refused on the raw token.
            long started = System.currentTimeMillis();
            SqlRuleResult result = evaluator.evaluate(subject, """
                    WITH RECURSIVE r AS (
                        SELECT 1::numeric AS n
                        UNION ALL
                        SELECT r.n + 1 FROM r
                    )
                    SELECT tx.transaction_id, tx.amount recursive FROM tx, r
                    """);
            assertFalse(result.ok(), "an unbounded recursion must never return a verdict");
            assertNotNull(result.rejectionReason(), "and it must not have to be cancelled to fail");
            assertTrue(result.rejectionReason().contains("'recursive'"), result.rejectionReason());
            assertNull(result.effectiveSql(), "nothing was sent to the planner");
            assertEquals(0, result.matchedCount());
            assertTrue(System.currentTimeMillis() - started < 1_000, "refusal is not a race");
        }

        @Test
        void theAllocatingRecursionIsRefusedRatherThanBoundedAfterTheFact() {
            // Why the word is refused rather than the query bounded: statement_timeout is checked
            // between executor steps, so it cannot interrupt a single allocation. This form doubles
            // a string each round, and when it was reachable it was measured holding roughly 0.7 GB
            // in one backend before PostgreSQL stopped it on the 1 GB varlena limit - 2.3 seconds
            // of a 5-second budget, spent on memory the timeout does not measure.
            SqlRuleResult result = evaluator.evaluate(subject, """
                    WITH RECURSIVE r AS (
                        SELECT 'aaaaaaaaaa'::text AS n
                        UNION ALL
                        SELECT r.n || r.n FROM r
                    )
                    SELECT tx.transaction_id, tx.amount recursive FROM tx, r
                    """);
            assertFalse(result.ok());
            assertNotNull(result.rejectionReason());
            assertNull(result.effectiveSql());
            assertEquals(0, result.matchedCount());
        }

        @Test
        void theRoleCarriesAMemoryAndADiskBoundOfItsOwn() throws SQLException {
            // The residual the recursion exposed, closed at the role rather than at the grammar:
            // even a query the validator accepts cannot ask the server for unbounded working
            // memory or unbounded temporary disk.
            assertEquals("4MB", roleSetting("work_mem"));
            assertEquals("64MB", roleSetting("temp_file_limit"));
            assertEquals("5s", roleSetting("statement_timeout"));
        }

        @Test
        void theIdListIsCappedWhileTheCountStaysTrue() throws SQLException {
            List<UUID> capped = runWrapped("SELECT tx.transaction_id FROM tx", 2).ids();
            assertEquals(2, capped.size(), "the row cap has to bound what crosses the wire");
            assertTrue(subjectTransactions.containsAll(capped));

            SqlRuleResult full = evaluator.evaluate(subject, "SELECT tx.transaction_id FROM tx");
            assertTrue(full.ok());
            assertEquals(subjectTransactions.size(), full.matchedCount(),
                    "the count is the database's and is never truncated");
        }

        @Test
        void anOversizedFragmentIsRefusedBeforeItIsEvenParsed() {
            String padding = "SELECT tx.transaction_id FROM tx WHERE tx.currency IN ("
                    + "'a', ".repeat(4000) + "'b')";
            SqlRuleResult result = evaluator.evaluate(subject, padding);
            assertFalse(result.ok());
            assertNotNull(result.rejectionReason());
            assertNull(result.effectiveSql(), "nothing that large may reach the planner");
        }
    }

    // ===========================================================================================
    // 8. Privilege probing.
    // ===========================================================================================

    @Nested
    @DisplayName("probing for privilege")
    class Privilege {

        @Test
        void settingARoleIsRefusedAndDeniedTwiceOver() {
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SET ROLE caa; SELECT tx.transaction_id FROM tx");
            assertFalse(result.ok());
            assertNotNull(result.rejectionReason());

            assertNotNull(rawError("SET ROLE caa"), "caa_readonly must not be able to become caa");
            assertNotNull(rawError("SET SESSION AUTHORIZATION caa"), "nor may it switch session auth");
        }

        @Test
        void movingTheSearchPathBuysNothingBecauseTheSchemaIsClosed() {
            assertNotNull(rawError("SET search_path = public; SELECT 1 FROM app_users"),
                    "search_path is not what keeps public.app_users closed - the grants are");
        }

        @Test
        void thereIsNoSecurityDefinerFunctionToBorrowPrivilegeFrom() throws SQLException {
            assertEquals(List.of(), rawStrings("""
                    SELECT n.nspname || '.' || p.proname
                    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE p.prosecdef AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                    """), "a SECURITY DEFINER function in a reachable schema would be a way out");
        }

        @Test
        void theRoleHoldsNoMembershipItCouldInherit() throws SQLException {
            assertEquals(List.of(), rawStrings("""
                    SELECT r.rolname FROM pg_auth_members m
                    JOIN pg_roles r ON r.oid = m.roleid
                    JOIN pg_roles g ON g.oid = m.member
                    WHERE g.rolname = current_user
                    """), "caa_readonly must be a member of nothing, pg_read_all_data included");
        }

        @Test
        void theTransactionIsReadOnlyAndScopedBeforeAnythingRuns() throws SQLException {
            assertEquals(List.of("on"), rawStrings("SHOW default_transaction_read_only"));
            assertEquals(List.of("caa_ro"), rawStrings("SHOW search_path"));
            assertEquals(List.of(subject.toString()),
                    rawStrings("SELECT current_setting('caa.customer_id')"));
        }

        @Test
        void withNoCustomerInScopeEveryViewIsEmpty() throws SQLException {
            try (Connection connection = readOnlyConnection(null);
                    Statement statement = connection.createStatement()) {
                for (String view : List.of("customers", "transactions", "card_activity",
                        "payment_activity", "crypto_activity")) {
                    try (ResultSet rows = statement.executeQuery(
                            "SELECT count(*) FROM caa_ro." + view)) {
                        rows.next();
                        assertEquals(0, rows.getLong(1),
                                "an unset scope must fail closed, not open: " + view);
                    }
                }
                connection.rollback();
            }
        }

        @Test
        void aFragmentCannotMoveTheScopeUnderneathItself() {
            SqlRuleResult result = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id FROM tx WHERE set_config('caa.customer_id', '"
                            + victim + "', true) <> ''");
            assertFalse(result.ok());
            assertNotNull(result.rejectionReason());
        }
    }

    // ===========================================================================================
    // 9. Alias laundering - the hole this test found, and the fix for it.
    // ===========================================================================================

    @Nested
    @DisplayName("alias laundering: names that used to slip past the validator")
    class AliasLaundering {

        /**
         * The finding. {@link RuleSqlValidator} pooled every declared name into one set and then
         * accepted {@code qualifier.column} whenever both halves were declared, so declaring
         * {@code public} and {@code app_users} as output-column aliases made
         * {@code FROM public.app_users} a name it had no objection to. Nothing but the database
         * grants stopped it - correct outcome, wrong ring, and the class javadoc claimed otherwise.
         *
         * <p>Fixed in two independent ways, both asserted here: declared names are now tracked by
         * kind, so a column alias can never qualify anything; and a qualified name has no legal form
         * where a table belongs, whatever it is called.
         */
        @Test
        void aSchemaQualifiedTableNameIsNowRefusedByTheValidatorItself() {
            String payload = """
                    SELECT tx.transaction_id, tx.amount AS public, tx.currency AS app_users
                    FROM tx, public.app_users
                    """;
            SqlRuleResult result = assertNothingEscaped(payload);
            assertNoHashAnywhere(result);
            assertNotNull(result.rejectionReason(),
                    "the validator, not the grants, has to be the ring that refuses this");
            assertNull(result.effectiveSql(), "and nothing may reach the database");
        }

        @Test
        void theSameLaunderingNoLongerReachesTheScopedViewsEither() {
            SqlRuleResult result = assertNothingEscaped("""
                    SELECT tx.transaction_id, tx.amount AS caa_ro, tx.currency AS transactions
                    FROM caa_ro.transactions tx
                    """);
            assertNotNull(result.rejectionReason(), "naming a schema has no accepted form");
        }

        /**
         * A schema nobody has heard of, to prove the refusal is structural rather than a word list:
         * neither half of {@code hidden.app_users} appears in any deny-list, and it is still refused
         * - once because a computed column cannot qualify a name, once because a qualified name
         * cannot be a table.
         */
        @Test
        void anUnknownSchemaIsRefusedWithoutBeingNamedInAnyList() {
            SqlRuleResult aliased = assertNothingEscaped("""
                    SELECT tx.transaction_id, tx.amount AS hidden, tx.currency AS app_users
                    FROM tx, hidden.app_users
                    """);
            assertNotNull(aliased.rejectionReason());
            assertTrue(aliased.rejectionReason().contains("not a table")
                            || aliased.rejectionReason().contains("not a relation"),
                    aliased.rejectionReason());

            SqlRuleResult plain = assertNothingEscaped("SELECT transaction_id FROM hidden.app_users");
            assertNotNull(plain.rejectionReason());
        }

        @Test
        void theBareKeywordFunctionsAreNoLongerASmuggledOracle() {
            // current_role took no parentheses, so it never reached the function allow-list, and
            // declaring it as an alias was enough to use it. It could not return a value - a rule
            // query returns transaction ids - but it steered a predicate, which is one bit of the
            // server's configuration per query. It is now unlaunderable.
            SqlRuleResult match = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id, tx.amount current_role FROM tx "
                            + "WHERE current_role = 'caa_readonly'");
            SqlRuleResult miss = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id, tx.amount current_role FROM tx "
                            + "WHERE current_role = 'postgres'");
            assertNotNull(match.rejectionReason(), "no query may ask who it is running as");
            assertNotNull(miss.rejectionReason());
            assertTrue(match.matchedTransactionIds().isEmpty());
            assertTrue(miss.matchedTransactionIds().isEmpty());
        }

        @Test
        void nothingLaunderedThroughAnAliasEverReturnsAForeignRow() {
            for (String payload : List.of(
                    "SELECT tx.transaction_id, tx.amount AS public, tx.currency AS app_users "
                            + "FROM tx, public.app_users",
                    "SELECT tx.transaction_id, tx.amount AS caa_ro, tx.currency AS customers "
                            + "FROM tx, caa_ro.customers",
                    "SELECT tx.transaction_id, tx.amount AS pg_temp, tx.currency AS loot "
                            + "FROM tx, pg_temp.loot",
                    "SELECT tx.transaction_id, tx.amount AS hidden, tx.currency AS loot "
                            + "FROM tx, hidden.loot",
                    "SELECT tx.transaction_id, tx.amount current_date FROM tx "
                            + "WHERE tx.created_at < current_date")) {
                SqlRuleResult result = assertNothingEscaped(payload);
                assertNotNull(result.rejectionReason(), payload);
            }
        }

        /**
         * The other half of the fix, and the half that could break real queries: hardening the
         * qualifier rule must not refuse the aliasing a rule query genuinely needs.
         */
        @Test
        void theAliasingALegitimateRuleQueryNeedsStillWorks() {
            SqlRuleResult correlated = evaluator.evaluate(subject, """
                    SELECT t.transaction_id
                    FROM tx t
                    WHERE (SELECT count(*) FROM tx w
                           WHERE w.created_at > t.created_at - INTERVAL '24 hours'
                             AND w.created_at <= t.created_at) >= 8
                    """);
            assertTrue(correlated.ok(), correlated.rejectionReason() + " / " + correlated.errorMessage());

            SqlRuleResult withCte = evaluator.evaluate(subject, """
                    WITH busy AS (
                        SELECT date_trunc('day', tx.created_at) AS bucket, count(*) AS n FROM tx
                        GROUP BY 1
                    )
                    SELECT t.transaction_id
                    FROM tx t
                    JOIN busy b ON b.bucket = date_trunc('day', t.created_at)
                    LEFT JOIN card c ON c.transaction_id = t.transaction_id
                    WHERE b.n >= 3 AND extract(hour FROM t.created_at) < 23
                    """);
            assertTrue(withCte.ok(), withCte.rejectionReason() + " / " + withCte.errorMessage());
            assertTrue(subjectTransactions.containsAll(withCte.matchedTransactionIds()));

            SqlRuleResult derived = evaluator.evaluate(subject, """
                    SELECT s.transaction_id
                    FROM (SELECT tx.transaction_id AS transaction_id, tx.amount AS value FROM tx) s
                    WHERE s.value > 100
                    """);
            assertTrue(derived.ok(), derived.rejectionReason() + " / " + derived.errorMessage());
        }
    }

    // ===========================================================================================
    // 10. The rule text itself is untrusted.
    // ===========================================================================================

    @Nested
    @DisplayName("injection through the rule condition")
    class AdversarialRuleText {

        @Test
        void aRuleThatOrdersTheAgentToReadThePasswordTableGetsNowhere() {
            // The prose an administrator could put in threshold_logic, and the query a model that
            // obeyed it would write. The sandbox never sees the prose - it only ever sees the SQL,
            // and the SQL is refused on its own terms.
            String whatACompliantModelWouldWrite = """
                    SELECT u.id AS transaction_id, u.username, u.password_hash
                    FROM app_users u
                    WHERE u.role = 'ADMIN'
                    """;
            SqlRuleResult result = assertNothingEscaped(whatACompliantModelWouldWrite);
            assertNoHashAnywhere(result);
            assertFalse(result.ok(), "an obeyed injection must not produce a verdict either");
        }

        @Test
        void aRuleThatOrdersTheAgentToLookAtAnotherCustomerGetsNowhere() {
            SqlRuleResult result = assertNothingEscaped(
                    "SELECT t.transaction_id FROM public.transactions t WHERE t.customer_id = '"
                            + victim + "'");
            assertFalse(result.ok());
        }

        /**
         * The gap the sandbox does not close, asserted so that nobody discovers it in production.
         *
         * <p>The sandbox guarantees that the comparison is PostgreSQL's, not the model's. It does
         * not - and structurally cannot - guarantee that the query asks the rule's question. A rule
         * condition is untrusted prose, and prose that dictates its own query reproduces exactly the
         * false negative this redesign was built to kill, with a clean audit trail and an
         * explanation that need not even lie.
         */
        @Test
        void aRuleTextThatDictatesItsOwnQueryCanStillManufactureAFalseNegative() {
            SqlRuleResult forcedNegative = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id FROM tx WHERE 1 = 0");
            assertTrue(forcedNegative.ok(),
                    "this is a well-formed query and the sandbox has no basis to refuse it");
            assertEquals(0, forcedNegative.matchedCount(),
                    "which is recorded as 'not triggered' and scores 0.00 - a false negative that "
                            + "no layer of the SQL sandbox can detect");
        }

        @Test
        void andSymmetricallyAFalsePositiveAtTheRulesFullWeight() {
            SqlRuleResult forcedPositive = evaluator.evaluate(subject,
                    "SELECT tx.transaction_id FROM tx");
            assertTrue(forcedPositive.ok());
            assertEquals(subjectTransactions.size(), forcedPositive.matchedCount(),
                    "a query that ignores the condition entirely fires the rule at full weight");
        }

        @Test
        void aQueryThatCannotAnswerAnythingIsUnjudgedRatherThanNotTriggered() {
            SqlRuleResult result = evaluator.evaluate(subject, "SELECT tx.amount * 2 FROM tx");
            assertFalse(result.ok(), "no transaction_id means no answer");
            assertNotNull(result.errorMessage());
            assertEquals(0, result.matchedCount(),
                    "and zero matches here must never be read as 'the rule did not fire'");
        }
    }

    // ===========================================================================================
    // 11. What the model is told when an attack fails.
    // ===========================================================================================

    @Nested
    @DisplayName("the error channel")
    class ErrorChannel {

        /**
         * A failing cast prints the value it could not convert, and that message is handed back to
         * the model and written to the persisted trace. Inside the sandbox the channel is starved -
         * the only readable rows are the subject's, which the agent already has through its own
         * tools, and card_pan is stored masked - but the channel is general, so any future widening
         * of the grants becomes an exfiltration primitive rather than a visibility problem.
         */
        @Test
        void aFailingCastPrintsInScopeDataAndNothingElse() {
            SqlRuleResult result = evaluator.evaluate(subject, """
                    SELECT tx.transaction_id FROM tx
                    WHERE (SELECT string_agg(card.card_pan, ',') FROM card)::int = 1
                    """);
            assertFalse(result.ok());
            assertNotNull(result.errorMessage());
            assertNoHashAnywhere(result);
            assertFalse(result.errorMessage().contains(victim.toString()),
                    "no value from outside the scope may appear in an error message");
        }

        @Test
        void everyErrorIsOneBoundedLineTheModelCanActOn() {
            for (String fragment : List.of(
                    "SELECT tx.transaction_id FROM app_users",
                    "SELECT tx.amount * 2 FROM tx",
                    "SELECT a.transaction_id FROM tx a, tx b, tx c, tx d, tx e, tx f, tx g")) {
                SqlRuleResult result = evaluator.evaluate(subject, fragment);
                assertFalse(result.ok(), fragment);
                String message = result.rejectionReason() != null
                        ? result.rejectionReason()
                        : result.errorMessage();
                assertNotNull(message, fragment);
                assertFalse(message.contains("\n"), "no stack traces or multi-line errors: " + fragment);
                assertTrue(message.length() <= 2000, "the message must be bounded: " + fragment);
            }
        }

        @Test
        void badInputNeverThrowsAndNeverJudgesARule() {
            for (String fragment : new String[] {null, "", "   ", "not sql at all", ")))"}) {
                SqlRuleResult result = evaluator.evaluate(subject, fragment);
                assertFalse(result.ok());
                assertEquals(0, result.matchedCount());
                assertTrue(result.matchedTransactionIds().isEmpty());
            }
            SqlRuleResult noCustomer = evaluator.evaluate(null, "SELECT tx.transaction_id FROM tx");
            assertFalse(noCustomer.ok());
            assertNotNull(noCustomer.rejectionReason());
        }
    }

    // ===========================================================================================
    // Harness.
    // ===========================================================================================

    /** A wrapped fragment run with the validator bypassed: the ids it produced, or the error. */
    private record Raw(List<UUID> ids, String error) {
    }

    /**
     * The invariant every attack in this class has to satisfy, stated so that it holds whichever
     * ring stopped the payload: either the fragment never produced a verdict, or it produced one
     * containing nothing but transactions the customer under analysis already owns.
     *
     * @return the result, so a test can go on to assert which ring it was
     */
    private SqlRuleResult assertNothingEscaped(String fragment) {
        SqlRuleResult result = evaluator.evaluate(subject, fragment);
        if (result.ok()) {
            assertTrue(subjectTransactions.containsAll(result.matchedTransactionIds()),
                    "a transaction that is not the subject's came back from: " + fragment);
            assertTrue(result.matchedCount() <= subjectTransactions.size(),
                    "the count exceeded the customer's own transactions: " + fragment);
        } else {
            assertTrue(result.rejectionReason() != null || result.errorMessage() != null,
                    "a failure has to say why: " + fragment);
            assertEquals(0, result.matchedCount(), "a failure is never a match: " + fragment);
            assertTrue(result.matchedTransactionIds().isEmpty(), fragment);
        }
        return result;
    }

    private static void assertNoHashAnywhere(SqlRuleResult result) {
        for (String text : new String[] {
                result.rejectionReason(), result.errorMessage(), result.effectiveSql()}) {
            if (text != null) {
                assertFalse(text.contains(HASH_MARKER), "a password hash reached the model: " + text);
                assertFalse(text.toLowerCase(Locale.ROOT).contains("password_hash="),
                        "a password hash reached the model: " + text);
            }
        }
    }

    /** Wraps a fragment and runs it on the read-only pool with the validator out of the way. */
    private Raw unvalidated(String fragment) {
        try {
            return runWrapped(fragment, properties.maxMatchedIds());
        } catch (SQLException e) {
            return new Raw(List.of(), e.getMessage().replaceAll("\\s+", " "));
        }
    }

    private Raw runWrapped(String fragment, int idCap) throws SQLException {
        try (Connection connection = readOnlyConnection(subject);
                PreparedStatement statement = connection.prepareStatement(
                        RuleSqlWrapper.wrap(fragment, idCap))) {
            statement.setQueryTimeout(properties.queryTimeoutSeconds());
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
            return new Raw(ids, null);
        }
    }

    /** Runs arbitrary SQL as the read-only role; the error, or null when it succeeded. */
    private String rawError(String sql) {
        try (Connection connection = readOnlyConnection(subject);
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(properties.queryTimeoutSeconds());
            statement.execute(sql);
            connection.rollback();
            return null;
        } catch (SQLException e) {
            return e.getMessage().replaceAll("\\s+", " ");
        }
    }

    /**
     * One setting recorded on the caa_readonly role itself, read through the owner connection.
     *
     * <p>Read from {@code pg_roles.rolconfig} rather than by asking the read-only session what its
     * current value is: the evaluator sets some of these per transaction as well, so a session
     * reading its own GUC would pass even if the role carried nothing.
     */
    private String roleSetting(String name) throws SQLException {
        try (Connection connection = ownerDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT split_part(config, '=', 2) FROM pg_roles r,"
                                + " unnest(coalesce(r.rolconfig, '{}')) AS config"
                                + " WHERE r.rolname = 'caa_readonly' AND config LIKE ? || '=%'")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
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
            connection.rollback();
            return values;
        }
    }

    /** A connection prepared exactly the way {@link PostgresRuleSqlEvaluator} prepares one. */
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

    /** Row counts, a content digest of every table an attack could touch, and the admin hash. */
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
                    "SELECT md5(string_agg(username || password_hash || role, '|'"
                            + " ORDER BY username)) FROM app_users")) {
                rows.next();
                state.put("app_users.digest", rows.getString(1));
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT password_hash FROM app_users WHERE username = 'admin'")) {
                rows.next();
                state.put("app_users.admin_hash", rows.getString(1));
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
