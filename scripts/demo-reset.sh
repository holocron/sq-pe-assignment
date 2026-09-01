#!/usr/bin/env bash
# Clears AI analysis history so a demo starts from a clean dashboard.
#
# Unlike db-reset.sh this is a *partial* reset and is safe to run against a RUNNING backend:
# it truncates only the two analysis tables and leaves everything a restart would have to rebuild.
#
#   wiped :  analysis_runs, risk_assessments
#   kept  :  customers, transactions and the three activity tables (the seed)
#            risk_rules             - your authored rules survive
#            knowledge_documents, document_chunks - no re-embedding, RAG keeps working
#            app_users, llm_settings - logins and model configuration
#
# No backend restart is needed: nothing here is loaded at startup. Use db-reset.sh instead when you
# want the full slate back (that one DOES require the backend to be stopped, and the next start
# replays the migrations, the seed and the knowledge-base bootstrap).
set -euo pipefail

PSQL="${PSQL:-/opt/homebrew/opt/postgresql@17/bin/psql}"
DB="${DB_NAME:-caa}"
FORCE="${1:-}"

if [ ! -x "$PSQL" ]; then
  echo "psql not found at '$PSQL' - set PSQL=/path/to/psql" >&2
  exit 1
fi

count() { "$PSQL" -d "$DB" -tAc "SELECT count(*) FROM $1;" 2>/dev/null || echo "?"; }

runs_before=$(count analysis_runs)
rows_before=$(count risk_assessments)

if [ "$runs_before" = "?" ]; then
  echo "Could not read '$DB'. Is Postgres up and the schema migrated?" >&2
  exit 1
fi

# An in-flight run holds no lock we would block on, but truncating underneath it strands the worker:
# it will finish its model calls and then fail to persist, leaving a FAILED run and wasted minutes.
running=$("$PSQL" -d "$DB" -tAc "SELECT count(*) FROM analysis_runs WHERE status = 'RUNNING';" 2>/dev/null || echo 0)
if [ "$running" -gt 0 ] && [ "$FORCE" != "--force" ]; then
  echo "$running analysis run(s) are still RUNNING."
  echo "Wiping now strands the worker - it would keep calling the model and then fail to persist."
  echo "Wait for them to finish, or re-run with --force to wipe anyway."
  exit 1
fi

"$PSQL" -d "$DB" -v ON_ERROR_STOP=1 -q -c 'TRUNCATE TABLE risk_assessments, analysis_runs;'

echo "Analysis history cleared."
echo "  analysis_runs     ${runs_before} -> $(count analysis_runs)"
echo "  risk_assessments  ${rows_before} -> $(count risk_assessments)"
echo "  kept: seed data, $(count risk_rules) rule(s), $(count document_chunks) knowledge chunk(s), $(count app_users) user(s)"
echo "No restart needed - run a new analysis from the customer page."
