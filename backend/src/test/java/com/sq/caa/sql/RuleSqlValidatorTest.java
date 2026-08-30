package com.sq.caa.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The first ring: what may be sent to PostgreSQL at all.
 *
 * <p>These tests are deliberately paired. Every attack asserted here is asserted again in
 * {@code RuleSqlSecurityTest} <b>with this class taken out of the loop</b>, because a validator that
 * is the only thing standing between a language model and a bank's database is a single point of
 * failure, and the point of the design is that it is not one.
 */
class RuleSqlValidatorTest {

    private final RuleSqlValidator validator = new RuleSqlValidator(8000);

    private void accepted(String fragment) {
        RuleSqlValidator.Verdict verdict = validator.validate(fragment);
        assertTrue(verdict.accepted(),
                () -> "should have been accepted but was refused with: " + verdict.rejectionReason());
        assertNotNull(verdict.fragment());
    }

    private void refused(String fragment, String expectedInReason) {
        RuleSqlValidator.Verdict verdict = validator.validate(fragment);
        assertFalse(verdict.accepted(), () -> "should have been refused: " + fragment);
        assertTrue(verdict.rejectionReason().toLowerCase().contains(expectedInReason.toLowerCase()),
                () -> "reason was: " + verdict.rejectionReason());
    }

    @Nested
    @DisplayName("queries a rule actually needs")
    class HappyPath {

        @Test
        void acceptsASimpleThreshold() {
            accepted("SELECT tx.transaction_id FROM tx WHERE tx.amount >= 9000");
        }

        @Test
        void acceptsTheVelocityWindowThatTheModelGotWrongByHand() {
            accepted("""
                    SELECT t.transaction_id
                    FROM tx t
                    WHERE (SELECT count(*) FROM tx w
                           WHERE w.created_at > t.created_at - INTERVAL '24 hours'
                             AND w.created_at <= t.created_at) >= 8
                    """);
        }

        @Test
        void acceptsACommonTableExpression() {
            accepted("""
                    WITH daily AS (
                        SELECT date_trunc('day', tx.created_at) AS bucket,
                               count(*) AS n,
                               sum(tx.amount) AS total
                        FROM tx
                        GROUP BY 1
                    )
                    SELECT t.transaction_id
                    FROM tx t
                    JOIN daily d ON d.bucket = date_trunc('day', t.created_at)
                    WHERE d.n >= 8 OR d.total > 50000
                    """);
        }

        @Test
        void acceptsJoinsAcrossTheDetailRelations() {
            accepted("""
                    SELECT tx.transaction_id
                    FROM tx
                    JOIN card ON card.transaction_id = tx.transaction_id
                    JOIN customer ON customer.customer_id = tx.customer_id
                    WHERE card.mcc_code IN ('7995', '6051')
                      AND card.card_present = false
                      AND customer.country <> 'CH'
                    """);
        }

        @Test
        void acceptsWindowFunctionsAndCasts() {
            accepted("""
                    SELECT t.transaction_id,
                           sum(t.amount) OVER (PARTITION BY t.currency ORDER BY t.created_at) AS running
                    FROM tx t
                    WHERE extract(hour FROM t.created_at) < 5
                      AND t.amount::numeric > 1000
                      AND upper(t.status) = 'FAILED'
                    """);
        }

        @Test
        void acceptsUnionAcrossTheAllowedRelations() {
            accepted("""
                    SELECT payment.transaction_id FROM payment
                    WHERE payment.receiver_bank_country IN ('RU', 'IR')
                    UNION
                    SELECT crypto.transaction_id FROM crypto WHERE crypto.exchange_name IS NULL
                    """);
        }

        @Test
        void acceptsSelectStar() {
            accepted("SELECT * FROM tx WHERE tx.status = 'Failed'");
        }

        @Test
        void stripsExactlyOneTrailingSemicolon() {
            RuleSqlValidator.Verdict verdict =
                    validator.validate("SELECT transaction_id FROM tx;  ");
            assertTrue(verdict.accepted(), verdict.rejectionReason());
            assertEquals("SELECT transaction_id FROM tx", verdict.fragment());
        }
    }

    @Nested
    @DisplayName("statement smuggling")
    class Smuggling {

        @Test
        void refusesASecondStatement() {
            refused("SELECT transaction_id FROM tx; DROP TABLE customers", "';' is not allowed");
        }

        @Test
        void refusesASecondStatementHiddenAfterAComment() {
            refused("SELECT transaction_id FROM tx -- ; DROP TABLE customers", "'--' comments");
        }

        @Test
        void refusesABlockComment() {
            refused("SELECT transaction_id /* sneaky */ FROM tx", "'/*' comments");
        }

        @Test
        void refusesACommentThatClosesOne() {
            refused("SELECT transaction_id FROM tx WHERE 1 = 1 */", "'*/' is not allowed");
        }

        @Test
        void refusesADanglingQuote() {
            refused("SELECT transaction_id FROM tx WHERE tx.status = 'Failed",
                    "unterminated string literal");
        }

        @Test
        void refusesDollarQuoting() {
            refused("SELECT transaction_id FROM tx WHERE tx.status = $$Failed$$", "'$' is not allowed");
        }

        @Test
        void refusesEscapeStringPrefixes() {
            refused("SELECT transaction_id FROM tx WHERE tx.status = E'Fail\\ned'",
                    "prefixed string literals");
        }

        @Test
        void refusesQuotedIdentifiersThatCouldHideACatalogName() {
            refused("SELECT transaction_id FROM \"pg_class\"", "double-quoted identifiers");
        }

        @Test
        void refusesParameterMarkersThatWouldShiftTheBoundCustomer() {
            refused("SELECT transaction_id FROM tx WHERE tx.customer_id = ?", "'?' is not allowed");
        }
    }

    @Nested
    @DisplayName("anything that is not a read")
    class NotAReadQuery {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "INSERT INTO customers VALUES (1)",
                "UPDATE tx SET amount = 0",
                "DELETE FROM tx",
                "DROP TABLE customers",
                "ALTER TABLE tx ADD COLUMN x int",
                "CREATE TABLE evil (i int)",
                "GRANT SELECT ON tx TO public",
                "REVOKE SELECT ON tx FROM caa",
                "TRUNCATE tx",
                "COPY tx TO '/tmp/leak.csv'",
                "CALL something()",
                "DO 'begin end'",
                "SET statement_timeout = 0",
                "VACUUM tx",
                "ANALYZE tx",
                "MERGE INTO tx USING tx ON true",
                "EXPLAIN SELECT transaction_id FROM tx"})
        void refusesWriteAndControlStatements(String fragment) {
            RuleSqlValidator.Verdict verdict = validator.validate(fragment);
            assertFalse(verdict.accepted(), () -> "should have been refused: " + fragment);
        }

        @Test
        void refusesASelectThatWritesARelation() {
            refused("SELECT transaction_id INTO stolen FROM tx", "INTO is not allowed");
        }

        @Test
        void refusesRowLocking() {
            refused("SELECT transaction_id FROM tx FOR UPDATE", "FOR is not allowed");
        }

        @Test
        void refusesAFragmentThatDoesNotStartWithSelect() {
            refused("TABLE tx", "has to start with SELECT");
        }
    }

    @Nested
    @DisplayName("reaching outside the customer")
    class OutsideTheScope {

        @Test
        void refusesTheUsersTable() {
            refused("SELECT transaction_id FROM app_users", "'app_users' is not something");
        }

        @Test
        void refusesPasswordHashesInASubquery() {
            refused("""
                    SELECT tx.transaction_id FROM tx
                    WHERE (SELECT count(*) FROM app_users WHERE password_hash IS NOT NULL) > 0
                    """, "not something a rule query can name");
        }

        @Test
        void refusesSchemaQualifiedNames() {
            refused("SELECT transaction_id FROM public.transactions", "'public' cannot appear");
            // The same refusal without relying on the schema being named in any list: a two-part
            // name has no legal form where a table belongs, whatever the schema is called.
            refused("SELECT transaction_id FROM hidden.transactions",
                    "'hidden' is not a relation the fragment may read");
            // A qualified name is refused where a table belongs even when both halves name real,
            // in-scope things - the shape alone is enough, so no declaration can rescue it.
            refused("SELECT tx.transaction_id FROM tx, tx.customer_id",
                    "is not a table this query may read");
        }

        /**
         * The hole a penetration test found: every declared name went into one set, so declaring
         * both halves of a schema-qualified table as output-column aliases made the qualified name
         * type-check. Only the database grants refused it. Both halves of the fix are asserted -
         * the schema words are unlaunderable, and a qualified name in FROM is refused on its shape.
         */
        @Test
        void refusesASchemaQualifiedTableLaunderedThroughColumnAliases() {
            refused("""
                    SELECT tx.transaction_id, tx.amount AS public, tx.currency AS app_users
                    FROM tx, public.app_users
                    """, "'public' cannot appear");
            refused("""
                    SELECT tx.transaction_id, tx.amount AS hidden, tx.currency AS app_users
                    FROM tx, hidden.app_users
                    """, "'hidden' is a column this query computes, not a table");
        }

        @Test
        void aColumnAliasCannotQualifyAColumn() {
            refused("SELECT tx.transaction_id, tx.amount AS ledger, ledger.amount FROM tx",
                    "'ledger' is a column this query computes, not a table");
        }

        @Test
        void refusesARelationThatIsNeitherOfTheFiveNorACteItDefines() {
            // The name is declared - as an output column - so it survives checkNames and is
            // refused by the relation-position pass, which is the ring being tested here.
            refused("SELECT tx.transaction_id, tx.amount AS ledger FROM ledger",
                    "'ledger' is not a relation this query may read");
        }

        @Test
        void refusesAFunctionCallWhereATableBelongs() {
            refused("SELECT tx.transaction_id FROM tx, count(1) c", "cannot appear in FROM");
        }

        @Test
        void refusesTheOtherApplicationTables() {
            refused("SELECT transaction_id FROM analysis_runs", "not something a rule query can name");
            refused("SELECT transaction_id FROM risk_assessments", "not something a rule query can name");
            refused("SELECT transaction_id FROM knowledge_documents", "not something a rule query can name");
            refused("SELECT transaction_id FROM document_chunks", "not something a rule query can name");
            refused("SELECT transaction_id FROM flyway_schema_history", "not something a rule query can name");
        }

        @Test
        void refusesCatalogEnumeration() {
            refused("SELECT transaction_id FROM information_schema.tables", "'information_schema'");
            refused("SELECT transaction_id FROM pg_catalog.pg_class", "'pg_catalog'");
            refused("SELECT transaction_id FROM pg_tables", "'pg_tables'");
            refused("SELECT transaction_id, pg_read_file('/etc/passwd') FROM tx", "'pg_read_file'");
        }

        @Test
        void refusesTheFilesystemAndNetworkFunctions() {
            refused("SELECT transaction_id FROM dblink('host=evil', 'SELECT 1') AS t(transaction_id uuid)",
                    "'dblink'");
            refused("SELECT lo_import('/etc/passwd') AS transaction_id FROM tx", "'lo_import'");
        }

        @Test
        void refusesTheSessionAndTheServerConfiguration() {
            refused("SELECT transaction_id FROM tx WHERE current_setting('caa.customer_id') <> ''",
                    "'current_setting'");
            refused("SELECT set_config('caa.customer_id', 'x', false) AS transaction_id FROM tx",
                    "'set_config'");
            refused("SELECT transaction_id, current_user FROM tx", "'current_user'");
            refused("SELECT transaction_id, version() FROM tx", "'version'");
        }

        @Test
        void refusesAColumnThatTheRelationDoesNotHave() {
            refused("SELECT card.transaction_id FROM card WHERE card.amount > 1",
                    "'card.amount' does not exist");
        }

        @Test
        void refusesAnUndefinedQualifier() {
            refused("SELECT tx.transaction_id FROM tx WHERE u.country = 'RU'",
                    "'u' is not a relation the fragment may read");
        }
    }

    @Nested
    @DisplayName("denial of service")
    class DenialOfService {

        @Test
        void refusesSleeping() {
            refused("SELECT transaction_id FROM tx WHERE pg_sleep(30) IS NULL", "'pg_sleep'");
        }

        @Test
        void refusesRowGenerators() {
            refused("SELECT transaction_id FROM tx, generate_series(1, 1000000000)",
                    "'generate_series' is not a function");
        }

        @Test
        void refusesStringBombs() {
            refused("SELECT transaction_id, repeat('x', 1000000000) FROM tx",
                    "'repeat' is not a function");
        }

        @Test
        void refusesAnythingNotOnTheFunctionAllowList() {
            refused("SELECT transaction_id, random() FROM tx", "'random' is not a function");
            refused("SELECT transaction_id, md5(status) FROM tx", "'md5' is not a function");
            refused("SELECT transaction_id FROM tx WHERE tx.status = ANY (query_to_xml('x', true, true, ''))",
                    "'query_to_xml'");
        }

        @Test
        void refusesSchemaQualifiedFunctionCalls() {
            refused("SELECT transaction_id, caa_ro.current_scope() FROM tx", "'caa_ro' cannot appear");
            refused("SELECT transaction_id, hidden.leak() FROM tx",
                    "functions cannot be called by a qualified name");
        }

        /**
         * A recursive CTE is the one construct in this grammar that can allocate without bound, and
         * {@code statement_timeout} cannot interrupt a single allocation. {@code recursive} was
         * reachable by declaring it as a column alias; it is now refused on the raw token, before
         * any declaration is read.
         */
        @Test
        void refusesRecursionEvenWhenTheWordIsDeclaredAsAnAlias() {
            refused("""
                    WITH RECURSIVE r AS (
                        SELECT 1::numeric AS n UNION ALL SELECT r.n + 1 FROM r
                    )
                    SELECT tx.transaction_id, tx.amount recursive FROM tx, r
                    """, "'recursive' cannot appear");
        }

        @Test
        void refusesTheParenthesisLessSessionFunctions() {
            refused("SELECT tx.transaction_id, tx.amount current_role FROM tx "
                    + "WHERE current_role = 'caa_readonly'", "'current_role' is not allowed");
            refused("SELECT tx.transaction_id, tx.amount current_date FROM tx "
                    + "WHERE tx.created_at < current_date", "'current_date' cannot appear");
        }

        @Test
        void refusesLateralAndValuesAndTablesample() {
            refused("SELECT tx.transaction_id, tx.amount lateral FROM tx, lateral (SELECT 1) l",
                    "'lateral' cannot appear");
            refused("SELECT tx.transaction_id, tx.amount values FROM tx, (values (1)) v",
                    "'values' cannot appear");
            refused("SELECT tx.transaction_id, tx.amount tablesample FROM tx tablesample system (1)",
                    "'tablesample' cannot appear");
        }

        @Test
        void refusesAFragmentLongerThanTheLimit() {
            String padding = " OR tx.amount = 1".repeat(600);
            refused("SELECT transaction_id FROM tx WHERE false" + padding, "characters, the limit is");
        }

        @Test
        void refusesUnbalancedParentheses() {
            refused("SELECT transaction_id FROM tx WHERE (tx.amount > 1", "unbalanced parentheses");
            refused("SELECT transaction_id FROM tx WHERE tx.amount > 1)", "unbalanced parentheses");
        }
    }

    @Nested
    @DisplayName("the shape the rest of the pipeline relies on")
    class Shape {

        @Test
        void refusesAFragmentThatCannotReturnAMatch() {
            refused("SELECT count(*) FROM tx WHERE tx.amount > 1000", "never mentions transaction_id");
        }

        @Test
        void refusesNothingAtAll() {
            refused(null, "empty");
            refused("   ", "empty");
            refused(";", "empty");
        }

        @Test
        void refusesArrayAndBraceSyntax() {
            refused("SELECT transaction_id FROM tx WHERE tx.status = ANY(ARRAY['a'])",
                    "'[' is not allowed");
        }

        @Test
        void refusesOperatorsThatAreNotOnTheList() {
            refused("SELECT transaction_id FROM tx WHERE tx.status ~ 'Fail'", "is not an operator");
            refused("SELECT transaction_id FROM tx WHERE tx.amount # 1", "is not an operator");
        }

        /**
         * PostgreSQL's scanner treats every byte above 0x7F as an identifier character;
         * {@code Character.isWhitespace} treats U+2003 as a separator. Two lexers that disagree
         * about where a token ends is how a validator approves a statement it did not read, so
         * every character the two could read differently is refused outright - the Cyrillic 'а'
         * hiding inside {@code status} and the em space between {@code SELECT} and {@code tx}
         * alike.
         */
        @Test
        void refusesEveryCharacterTheTwoLexersWouldReadDifferently() {
            refused("SELECT transaction_id FROM tx WHERE tx.st\u0430tus = 'Failed'", "U+0430");
            refused("SELECT\u2003tx.transaction_id FROM tx", "U+2003");
            refused("SELECT\u00a0tx.transaction_id FROM tx", "U+00A0");
            refused("SELECT tx.transaction_id\u200b FROM tx", "U+200B");
        }

        @Test
        void acceptsTheSixCharactersPostgresCallsWhitespace() {
            accepted("SELECT\ttx.transaction_id\nFROM\r\ntx\013WHERE\ftx.amount > 1");
        }

        @Test
        void nonAsciiInsideAStringLiteralIsStillData() {
            accepted("SELECT tx.transaction_id FROM tx JOIN card ON card.transaction_id = "
                    + "tx.transaction_id WHERE card.merchant_name = 'Café Zürich'");
        }
    }
}
