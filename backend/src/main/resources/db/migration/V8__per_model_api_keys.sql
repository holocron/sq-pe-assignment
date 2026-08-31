-- V8: per-model API keys on llm_settings.
--
-- Also widens the sentinel primary key from SMALLINT to BIGINT: the JPA entity maps the id as a
-- Long, and ddl-auto=validate rejects the int2 column. The value stays pinned to 1 by the
-- existing CHECK constraint; the change is purely a storage-type alignment.
ALTER TABLE llm_settings ALTER COLUMN id TYPE BIGINT;
--
-- The single api_key column is replaced by chat_api_key and embed_api_key so a deployment can
-- point the chat model and the embedding model at endpoints with different credentials (or at a
-- local model server that takes no key at all). Any previously stored key is copied into BOTH new
-- columns: with one endpoint the old key was used for both kinds of calls, so that is the only
-- lossless split.
--
-- Column semantics after this migration:
--   * a NON-EMPTY value is the key sent for that model's calls;
--   * an EMPTY string is an explicit "no key" (local model servers) - it does NOT fall back to
--     OPENAI_API_KEY, because a database row drives the whole configuration once it exists;
--   * NULL only occurs on rows migrated from a NULL api_key and reads the same as empty.
-- Both keys are stored in PLAINTEXT, the same deliberate prototype trade-off V7 documented for
-- api_key; the API never returns them (GET exposes only chatApiKeySet / embedApiKeySet).
ALTER TABLE llm_settings
    ADD COLUMN chat_api_key  TEXT,
    ADD COLUMN embed_api_key TEXT;

UPDATE llm_settings
SET chat_api_key = api_key,
    embed_api_key = api_key;

ALTER TABLE llm_settings DROP COLUMN api_key;
