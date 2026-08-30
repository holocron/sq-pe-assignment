#!/usr/bin/env bash
# First-time database setup. Idempotent - safe to re-run.
#
# Must be run by a Postgres SUPERUSER (on a Homebrew install that is your own macOS user).
# CREATE EXTENSION requires superuser, and the application role needs to OWN the public schema,
# otherwise Flyway fails with "no schema has been selected to create in".
set -euo pipefail
PSQL="${PSQL:-/opt/homebrew/opt/postgresql@17/bin/psql}"
DB="${DB_NAME:-caa}"
ROLE="${DB_ROLE:-caa}"
PASS="${DB_PASSWORD:-caa}"

"$PSQL" -d postgres -v ON_ERROR_STOP=1 <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${ROLE}') THEN
    CREATE ROLE ${ROLE} LOGIN PASSWORD '${PASS}';
  END IF;
END \$\$;
SQL

if ! "$PSQL" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='${DB}'" | grep -q 1; then
  "$PSQL" -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE ${DB} OWNER ${ROLE};"
fi

# Extensions need superuser; ownership of public is what lets the app role run migrations.
"$PSQL" -d "$DB" -v ON_ERROR_STOP=1 <<SQL
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
ALTER SCHEMA public OWNER TO ${ROLE};
GRANT ALL ON SCHEMA public TO ${ROLE};
SQL

# Two more things only a superuser can hand over, both needed by V5__readonly_role.sql, which
# builds the least-privilege role that the agent's SQL executes as:
#
#   CREATEROLE       so the migration can create caa_readonly at all. Without it V5 stops with an
#                    exception naming the exact statement to run, rather than granting privileges
#                    to a principal that does not exist.
#   SET ON PARAMETER temp_file_limit is a superuser-only GUC, so ALTER ROLE caa_readonly SET
#                    temp_file_limit is refused unless the right to set it is delegated. It caps
#                    the disk one runaway rule query can spill; statement_timeout bounds only time.
#                    V5 downgrades to a WARNING when this grant is absent, so setup stays optional.
"$PSQL" -d "$DB" -v ON_ERROR_STOP=1 <<SQL
ALTER ROLE ${ROLE} CREATEROLE;
GRANT SET ON PARAMETER temp_file_limit TO ${ROLE};
SQL

echo "Database '${DB}' ready for role '${ROLE}' (pgvector + uuid-ossp installed, public schema owned by ${ROLE}, CREATEROLE and temp_file_limit delegated)."
