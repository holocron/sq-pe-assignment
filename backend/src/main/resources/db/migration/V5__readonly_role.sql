-- =============================================================================
-- V5 - The least-privilege database role the ReAct agent's SQL runs as.
--
-- WHY THIS EXISTS
-- Rule verdicts are no longer the model's arithmetic: the agent writes a SELECT
-- fragment that answers the rule condition, PostgreSQL computes the answer, and
-- "triggered" is derived mechanically from whether rows came back. That means
-- model-authored SQL now executes against a bank's customer database, so it must
-- execute as a principal that CANNOT do anything other than read the activity of
-- the one customer under analysis.
--
-- This migration builds the innermost two rings of that defence:
--
--   ring 1  a login role, caa_readonly, with no privilege anywhere in the
--           database except SELECT on the five customer-scoped views below.
--           No app_users (password hashes), no analysis_runs, no risk_rules, no
--           risk_assessments, no knowledge_documents, no document_chunks, no
--           flyway_schema_history, no CREATE anywhere, no writes anywhere.
--
--   ring 2  the views are scoped to a single customer by construction. Each one
--           filters on caa_ro.current_scope(), which reads the transaction-local
--           GUC caa.customer_id that com.sq.caa.sql.PostgresRuleSqlEvaluator sets
--           - as a bound parameter - immediately before it runs the query. Unset
--           or empty, the GUC yields NULL and every view returns ZERO rows: the
--           failure mode is "sees nothing", never "sees everyone".
--
-- The remaining rings live in Java: com.sq.caa.sql.RuleSqlValidator (allow-listed
-- identifiers, single statement, no comments) and RuleSqlWrapper (the fragment is
-- nested inside CTEs pre-filtered by a JDBC parameter, and the ids it returns are
-- intersected back with that customer's transactions). Each ring holds alone.
--
-- DELIBERATE DEVIATION, worth reading before changing it: the grant is on five
-- VIEWS in schema caa_ro, not on the five base tables in schema public. Granting
-- SELECT on public.transactions would have made "SELECT * FROM public.transactions"
-- a working cross-customer read for anything that got past the validator, which
-- is precisely the guarantee this layer must not depend on the validator for.
-- Reading through owner-privileged, customer-scoped views is strictly stronger:
-- the role holds no privilege at all on the base tables and no USAGE on schema
-- public, so naming a real table fails with "permission denied for schema public"
-- before a single row is touched. The five underlying tables are still the only
-- data reachable, and the assignment tables themselves are untouched - no columns,
-- constraints, indexes or row-level-security settings are altered by this file.
--
-- PRIVILEGE NEEDED TO RUN IT: the migration runs as the schema owner (caa). Two
-- things are delegated once per environment by a superuser, both of them done by
-- scripts/db-setup.sh:
--     ALTER ROLE caa CREATEROLE;                        -- required
--     GRANT SET ON PARAMETER temp_file_limit TO caa;    -- optional, see below
-- Without CREATEROLE and with no caa_readonly present, this file fails loudly with
-- the exact SQL a DBA has to run - it never proceeds to hand out grants to a
-- principal that does not exist. Without the parameter grant it raises a WARNING
-- and carries on, because temp_file_limit is defence in depth over the timeout and
-- work_mem rather than a functional requirement. Every statement below is
-- idempotent and safe to re-run.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- The role.
--
-- NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOINHERIT/NOREPLICATION/NOBYPASSRLS are all
-- defaults; they are spelled out because this is the one object in the schema
-- whose attributes are load-bearing, and a reviewer should not have to know the
-- defaults to audit it. CONNECTION LIMIT bounds the read-only pool at the server
-- as well as at HikariCP.
--
-- The password is a local-demo default, exactly like the caa/caa owner role. The
-- shipped pg_hba.conf trusts loopback connections, so it is not what protects
-- anything here - the privilege set is. Override it with DB_READONLY_PASSWORD
-- (see caa.sql.datasource.password) together with an ALTER ROLE ... PASSWORD in
-- any deployment where the network is not the loopback interface.
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'caa_readonly') THEN
        RETURN;
    END IF;

    IF NOT (SELECT rolsuper OR rolcreaterole FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION
            'role caa_readonly is missing and % cannot create it', current_user
            USING HINT = 'Run once as a superuser: ALTER ROLE ' || current_user
                      || ' CREATEROLE;  -- or provision the role directly: '
                      || 'CREATE ROLE caa_readonly LOGIN PASSWORD ''caa_readonly'' '
                      || 'NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION '
                      || 'NOBYPASSRLS CONNECTION LIMIT 8;';
    END IF;

    CREATE ROLE caa_readonly
        LOGIN PASSWORD 'caa_readonly'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS
        CONNECTION LIMIT 8;
END
$$;

-- -----------------------------------------------------------------------------
-- Role-level defaults.
--
-- Every one of these is also set per transaction by the evaluator, because a
-- server-side default is not a guarantee the application can rely on. They are
-- set here as well so that a connection opened by any other means - psql, a
-- forgotten script - is still read-only, still times out, and still cannot see
-- schema public through an unqualified name.
--
-- ALTER ROLE needs admin rights over the role. When caa created the role above it
-- has them; when a DBA provisioned the role out of band it may not, and that is a
-- warning rather than an error precisely because the evaluator sets all of it per
-- transaction anyway.
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    ALTER ROLE caa_readonly SET default_transaction_read_only = on;
    ALTER ROLE caa_readonly SET statement_timeout = '5s';
    ALTER ROLE caa_readonly SET lock_timeout = '2s';
    ALTER ROLE caa_readonly SET idle_in_transaction_session_timeout = '30s';
    ALTER ROLE caa_readonly SET search_path = caa_ro;
    -- Time is not the only resource a query spends, and statement_timeout bounds only time.
    -- It is checked between executor steps, so it cannot interrupt a single large allocation:
    -- a penetration test measured one backend transiently holding ~0.7 GB before PostgreSQL
    -- stopped it on the varlena size limit rather than on the clock. The construct that got
    -- there (a recursive CTE) is now refused outright by RuleSqlValidator, and these two bound
    -- what an accepted query can still ask for. 4 MB is generous for a rule query: the largest
    -- customer here has a few hundred transactions.
    ALTER ROLE caa_readonly SET work_mem = '4MB';
    ALTER ROLE caa_readonly SET hash_mem_multiplier = 1;
    BEGIN
        -- temp_file_limit is superuser-only, so this needs the right to set it to have been
        -- delegated (scripts/db-setup.sh does it: GRANT SET ON PARAMETER temp_file_limit TO caa).
        -- It is the disk half of the same bound. Optional on purpose: work_mem, the timeout and the
        -- validator all still apply without it, so a missing grant must not fail the migration.
        ALTER ROLE caa_readonly SET temp_file_limit = '64MB';
    EXCEPTION
        WHEN insufficient_privilege THEN
            RAISE WARNING 'temp_file_limit not applied to caa_readonly: %', SQLERRM
                USING HINT = 'Run as a superuser: GRANT SET ON PARAMETER temp_file_limit TO caa; '
                          || 'then re-run this migration, or set it directly with '
                          || 'ALTER ROLE caa_readonly SET temp_file_limit = ''64MB'';';
    END;
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE WARNING 'could not apply role defaults to caa_readonly as %: %', current_user, SQLERRM
            USING HINT = 'Run as a superuser: ALTER ROLE caa_readonly SET '
                      || 'default_transaction_read_only = on; (and statement_timeout = ''5s'', '
                      || 'lock_timeout = ''2s'', idle_in_transaction_session_timeout = ''30s'', '
                      || 'search_path = caa_ro). The evaluator sets all of these per transaction, '
                      || 'so this is defence in depth, not a functional requirement.';
END
$$;

-- -----------------------------------------------------------------------------
-- The scoped read schema.
-- -----------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS caa_ro;

COMMENT ON SCHEMA caa_ro IS
    'The only schema caa_readonly may enter: five single-customer views over the activity tables.';

-- The scope itself. STABLE (not IMMUTABLE - it reads a GUC), PARALLEL SAFE so the
-- views can still be scanned in parallel, and missing_ok = true so that an unset
-- GUC yields NULL and therefore no rows, instead of raising and leaking the fact
-- that the setting exists.
CREATE OR REPLACE FUNCTION caa_ro.current_scope() RETURNS uuid
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
    AS $$ SELECT nullif(current_setting('caa.customer_id', true), '')::uuid $$;

COMMENT ON FUNCTION caa_ro.current_scope() IS
    'The customer every caa_ro view is restricted to, from the transaction-local GUC caa.customer_id. NULL when unset, which yields zero rows everywhere.';

-- WITH (security_barrier) stops the planner from pushing a caller-supplied
-- predicate underneath the customer filter. Without it a cheap, non-leakproof
-- qual in the agent's fragment could be evaluated against rows the view is meant
-- to have removed and leak them through an error message or timing.
--
-- DROP + CREATE rather than CREATE OR REPLACE: replacing a view cannot change its
-- column list, so a re-run after any edit here would fail. CASCADE is not used -
-- nothing but this file ever depends on these views, and an unexpected dependency
-- should stop the migration rather than be silently dropped.
--
-- activity_type is projected as text on purpose. The enum type lives in schema
-- public, which caa_readonly cannot enter, so an agent fragment writing
-- 'CARD'::activity_type would fail on a schema permission error that has nothing
-- to do with its rule. As text it compares the obvious way and the role needs no
-- object outside caa_ro.
DROP VIEW IF EXISTS caa_ro.crypto_activity;
DROP VIEW IF EXISTS caa_ro.payment_activity;
DROP VIEW IF EXISTS caa_ro.card_activity;
DROP VIEW IF EXISTS caa_ro.transactions;
DROP VIEW IF EXISTS caa_ro.customers;

CREATE VIEW caa_ro.customers WITH (security_barrier) AS
SELECT c.customer_id,
       c.last_name,
       c.first_name,
       c.dob,
       c.country
FROM public.customers c
WHERE c.customer_id = caa_ro.current_scope();

CREATE VIEW caa_ro.transactions WITH (security_barrier) AS
SELECT t.transaction_id,
       t.customer_id,
       t.activity_type::text AS activity_type,
       t.amount,
       t.currency,
       t.status,
       t.created_at
FROM public.transactions t
WHERE t.customer_id = caa_ro.current_scope();

CREATE VIEW caa_ro.card_activity WITH (security_barrier) AS
SELECT ca.transaction_id,
       ca.card_pan,
       ca.card_type,
       ca.merchant_name,
       ca.mcc_code,
       ca.card_present,
       ca.authorization_code,
       ca.decline_reason
FROM public.card_activity ca
JOIN public.transactions t ON t.transaction_id = ca.transaction_id
WHERE t.customer_id = caa_ro.current_scope();

CREATE VIEW caa_ro.payment_activity WITH (security_barrier) AS
SELECT pa.transaction_id,
       pa.payment_method,
       pa.sender_account,
       pa.receiver_account,
       pa.receiver_bank_country
FROM public.payment_activity pa
JOIN public.transactions t ON t.transaction_id = pa.transaction_id
WHERE t.customer_id = caa_ro.current_scope();

CREATE VIEW caa_ro.crypto_activity WITH (security_barrier) AS
SELECT cr.transaction_id,
       cr.blockchain,
       cr.wallet_address_from,
       cr.wallet_address_to,
       cr.tx_hash,
       cr.exchange_name
FROM public.crypto_activity cr
JOIN public.transactions t ON t.transaction_id = cr.transaction_id
WHERE t.customer_id = caa_ro.current_scope();

COMMENT ON VIEW caa_ro.customers        IS 'public.customers restricted to caa_ro.current_scope().';
COMMENT ON VIEW caa_ro.transactions     IS 'public.transactions restricted to caa_ro.current_scope(); activity_type as text.';
COMMENT ON VIEW caa_ro.card_activity    IS 'public.card_activity for transactions of caa_ro.current_scope().';
COMMENT ON VIEW caa_ro.payment_activity IS 'public.payment_activity for transactions of caa_ro.current_scope().';
COMMENT ON VIEW caa_ro.crypto_activity  IS 'public.crypto_activity for transactions of caa_ro.current_scope().';

-- -----------------------------------------------------------------------------
-- Take everything away first.
--
-- Order matters: revoke before grant, so that re-running this file cannot leave a
-- privilege behind that an earlier version of it handed out. PUBLIC is a role
-- every login role inherits from, so its default grants are revoked too -
-- otherwise caa_readonly would arrive holding USAGE on schema public (the
-- PostgreSQL default) and could at least name public.app_users.
--
-- caa owns the database, schema public and every table in it, and owner rights
-- are held by a separate ACL entry, so none of this touches the application's own
-- connection.
-- -----------------------------------------------------------------------------
REVOKE ALL ON DATABASE caa FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;

REVOKE ALL ON DATABASE caa FROM caa_readonly;
REVOKE ALL ON SCHEMA public FROM caa_readonly;
REVOKE ALL PRIVILEGES ON ALL TABLES    IN SCHEMA public FROM caa_readonly;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM caa_readonly;
-- No matching REVOKE ... ON ALL FUNCTIONS IN SCHEMA public: EXECUTE on the uuid-ossp
-- and pgvector functions is held by PUBLIC and those functions are owned by the
-- role that installed the extensions, so caa cannot revoke it and PostgreSQL
-- answers with a WARNING per function - a hundred lines of noise in the migration
-- log for no change in privilege. What actually closes them is above: caa_readonly
-- has no USAGE on schema public, and a function cannot be named in a schema you
-- may not enter.
REVOKE ALL ON SCHEMA caa_ro FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA caa_ro FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA caa_ro FROM caa_readonly;
REVOKE ALL ON FUNCTION caa_ro.current_scope() FROM PUBLIC;

-- Anything created in public from now on is closed to PUBLIC by default too, so a
-- future table or function cannot quietly become reachable. (Tables grant nothing
-- to PUBLIC by default; functions grant EXECUTE, which is the one that matters.)
ALTER DEFAULT PRIVILEGES FOR ROLE caa IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE caa IN SCHEMA caa_ro REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- -----------------------------------------------------------------------------
-- Give back exactly what the evaluator needs, and nothing else.
--
-- CONNECT on the database. USAGE on caa_ro only. SELECT on five views. EXECUTE on
-- the scope function, because a function used inside a view is checked against the
-- calling role, not the view owner. No TEMPORARY (a temp table is a write and a
-- place to stage stolen rows), no USAGE on schema public, no INSERT/UPDATE/DELETE
-- anywhere, no column-level exceptions.
-- -----------------------------------------------------------------------------
GRANT CONNECT ON DATABASE caa TO caa_readonly;
GRANT USAGE ON SCHEMA caa_ro TO caa_readonly;
GRANT SELECT ON caa_ro.customers        TO caa_readonly;
GRANT SELECT ON caa_ro.transactions     TO caa_readonly;
GRANT SELECT ON caa_ro.card_activity    TO caa_readonly;
GRANT SELECT ON caa_ro.payment_activity TO caa_readonly;
GRANT SELECT ON caa_ro.crypto_activity  TO caa_readonly;
GRANT EXECUTE ON FUNCTION caa_ro.current_scope() TO caa_readonly;

-- What is deliberately NOT closed here, so nobody assumes it is:
--   * pg_catalog and information_schema stay readable, as they are for every role
--     in every PostgreSQL database. Closing them needs superuser ownership of
--     objects this migration does not own, and it would break the JDBC driver's
--     own metadata queries. They expose object names, never row data - no table in
--     schema public can be read through them without table privileges - and
--     RuleSqlValidator refuses any fragment that so much as names them.
--   * pg_sleep and the other PUBLIC-executable pg_catalog functions. Same reason.
--     They are answered by statement_timeout (server side, set per transaction and
--     on the role) and by the validator's function allow-list.
