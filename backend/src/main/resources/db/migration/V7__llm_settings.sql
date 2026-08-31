-- V7: runtime-configurable LLM endpoints.
--
-- llm_settings is a SINGLE-ROW table (id is pinned to 1 by constraint): at most one admin override
-- of the model configuration exists. An ABSENT row means "use the environment configuration"
-- (LLM_BASE_URL / LLM_CHAT_MODEL / LLM_EMBED_MODEL / OPENAI_API_KEY) - no row is seeded here, so a
-- fresh deployment behaves exactly as before. A row is written by PUT /api/admin/llm-settings and
-- takes effect without a restart.
--
-- api_key is stored in PLAINTEXT. That is a deliberate prototype trade-off: the process already
-- holds the key in memory and the database is local; the README's "Model access" section says so
-- out loud. The API never returns it (GET exposes only apiKeySet: boolean).
--
-- embed_dimension records what document_chunks.embedding was altered to when the embedding model
-- changed at runtime. The static column type here cannot know that number, so the ALTER runs as
-- guarded DDL from MutableLlmSettingsService; this column is the record that lets a restart verify
-- the schema against the right dimension instead of the boot default.
CREATE TABLE llm_settings (
    id               SMALLINT    NOT NULL DEFAULT 1,
    base_url         TEXT        NOT NULL,
    chat_model       TEXT        NOT NULL,
    embed_model      TEXT        NOT NULL,
    embed_dimension  INTEGER     NOT NULL,
    api_key          TEXT,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       VARCHAR(64),
    CONSTRAINT pk_llm_settings PRIMARY KEY (id),
    CONSTRAINT ck_llm_settings_single_row CHECK (id = 1),
    CONSTRAINT ck_llm_settings_embed_dimension CHECK (embed_dimension > 0)
);

-- The original upload bytes, kept so a change of embedding model can re-extract, re-chunk and
-- re-embed a document without asking the administrator to upload it again. Nullable because rows
-- that predate this column have none; the re-embed job counts those as failed with a clear reason
-- rather than re-chunking from concatenated chunk text.
ALTER TABLE knowledge_documents ADD COLUMN source_bytes BYTEA;
COMMENT ON COLUMN knowledge_documents.source_bytes IS
    'Original upload, retained for re-embedding after an embedding model change. NULL for documents uploaded before V7.';
