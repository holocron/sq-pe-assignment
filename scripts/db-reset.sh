#!/usr/bin/env bash
# Drops and recreates the caa schema, so the next backend start replays every Flyway migration
# (baseline -> app tables -> seed) against a clean database.
set -euo pipefail
PSQL="${PSQL:-/opt/homebrew/opt/postgresql@17/bin/psql}"

"$PSQL" -d caa -v ON_ERROR_STOP=1 -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
"$PSQL" -d caa -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION IF NOT EXISTS vector; CREATE EXTENSION IF NOT EXISTS "uuid-ossp";'
echo "Database reset. Start the backend to re-apply migrations and seed data."
