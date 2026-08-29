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

echo "Database '${DB}' ready for role '${ROLE}' (pgvector + uuid-ossp installed, public schema owned by ${ROLE})."
