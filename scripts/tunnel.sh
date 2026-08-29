#!/usr/bin/env bash
# Opens the SSH tunnel to the lemonade model router on holominix.
#
# lemonade (the `lemond` daemon) is a ROUTER: one OpenAI-compatible endpoint on port 13305 that
# fronts every model and dispatches by model id, spawning/reusing llama-server backends per model.
# So we forward exactly one port, not one per model.
#
#   localhost:13305/api/v1  ->  OpenAI-compatible router
#     chat model      : gpt-oss-120b-GGUF       (reasoning + native tool calling)
#     embedding model : Qwen3-Embedding-4B-GGUF (2560 dimensions)
#
# The backend points spring.ai.openai.base-url at http://localhost:13305/api/v1 and selects the
# model by id, so switching models is a config change with no new tunnel.
set -euo pipefail

HOST="${MODEL_HOST:-holominix}"
PORT="${MODEL_PORT:-13305}"

if curl -s -m 5 -o /dev/null "http://localhost:${PORT}/api/v1/models"; then
  echo "Router tunnel already up on :${PORT}"
  exit 0
fi

echo "Opening tunnel to ${HOST}..."
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -L "${PORT}:127.0.0.1:${PORT}" "${HOST}" &

for _ in $(seq 1 20); do
  sleep 1
  if curl -s -m 5 -o /dev/null "http://localhost:${PORT}/api/v1/models"; then
    echo "Router up: http://localhost:${PORT}/api/v1  (models: $(curl -s -m 5 "http://localhost:${PORT}/api/v1/models" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]))'))"
    exit 0
  fi
done

echo "Tunnel failed. Check that 'ssh ${HOST}' works and lemonade is running." >&2
exit 1
