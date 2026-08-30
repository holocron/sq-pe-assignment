#!/usr/bin/env bash
# Drops and recreates the schema so the next backend start replays every Flyway migration
# (baseline -> app tables -> seed) against a clean database.
#
# Recreating `public` makes it owned by the superuser running this script, so ownership must be
# handed back to the application role - otherwise Flyway fails with
# "ERROR: no schema has been selected to create in".
set -euo pipefail
PSQL="${PSQL:-/opt/homebrew/opt/postgresql@17/bin/psql}"
DB="${DB_NAME:-caa}"
ROLE="${DB_ROLE:-caa}"

# caa_ro goes too: it holds the single-customer views the agent's SQL role reads through, and
# they are rebuilt by V5__readonly_role.sql on the next start. Dropping public alone would cascade
# the views away and leave the schema and its scope function behind, which is a partial slate.
# The caa_readonly LOGIN role itself is deliberately kept - dropping a role needs superuser and
# V5 is idempotent about a role that already exists.
"$PSQL" -d "$DB" -v ON_ERROR_STOP=1 <<SQL
DROP SCHEMA IF EXISTS caa_ro CASCADE;
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
ALTER SCHEMA public OWNER TO ${ROLE};
GRANT ALL ON SCHEMA public TO ${ROLE};
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
SQL

echo "Database reset. Start the backend to re-apply migrations and seed data."
