-- =============================================================================
-- V2 - Supporting application tables.
--
-- The assignment requires login, RAG over uploaded policy documents and persisted
-- AI analysis results, but did not schema those out. These tables carry that
-- state. The seven assignment tables in V1 are untouched.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

-- -----------------------------------------------------------------------------
-- app_users - operators and administrators
-- -----------------------------------------------------------------------------
CREATE TABLE app_users (
    user_id       UUID         NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(120) NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT (now() AT TIME ZONE 'utc'),
    CONSTRAINT pk_app_users        PRIMARY KEY (user_id),
    CONSTRAINT uq_app_users_username UNIQUE (username),
    CONSTRAINT ck_app_users_role   CHECK (role IN ('ADMIN', 'OPERATOR'))
);

COMMENT ON COLUMN app_users.password_hash IS 'BCrypt hash.';

-- -----------------------------------------------------------------------------
-- analysis_runs - one row per AI analysis (one assessment_id).
-- The per-rule detail of a run lives in risk_assessments.
-- -----------------------------------------------------------------------------
CREATE TABLE analysis_runs (
    assessment_id     UUID          NOT NULL,
    customer_id       UUID          NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    risk_level        VARCHAR(20),
    total_score       DECIMAL(10,2),
    summary           TEXT,
    recommendations   TEXT,
    rules_total       INT           NOT NULL DEFAULT 0,
    rules_evaluated   INT           NOT NULL DEFAULT 0,
    coverage_complete BOOLEAN       NOT NULL DEFAULT FALSE,
    model             VARCHAR(120),
    steps             INT           NOT NULL DEFAULT 0,
    duration_ms       BIGINT,
    trace             JSONB,
    error             TEXT,
    requested_by      VARCHAR(64),
    created_at        TIMESTAMP     NOT NULL,
    completed_at      TIMESTAMP,
    CONSTRAINT pk_analysis_runs PRIMARY KEY (assessment_id),
    CONSTRAINT fk_analysis_runs_customer FOREIGN KEY (customer_id)
        REFERENCES customers (customer_id) ON DELETE CASCADE,
    CONSTRAINT ck_analysis_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_analysis_runs_risk_level CHECK (risk_level IS NULL OR risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

COMMENT ON COLUMN analysis_runs.assessment_id     IS 'Same identifier as risk_assessments.assessment_id.';
COMMENT ON COLUMN analysis_runs.coverage_complete IS 'TRUE when the agent judged every applicable rule; a COMPLETED run always has TRUE, a run that left a rule unjudged is stored FAILED.';
COMMENT ON COLUMN analysis_runs.trace             IS 'Full ReAct transcript: {"steps":[{"n":1,"type":"tool_call",...}]}.';

CREATE INDEX idx_analysis_runs_customer_created ON analysis_runs (customer_id, created_at DESC);
CREATE INDEX idx_analysis_runs_created          ON analysis_runs (created_at DESC);
CREATE INDEX idx_analysis_runs_status           ON analysis_runs (status);

-- -----------------------------------------------------------------------------
-- knowledge_documents - uploaded .docx/.pdf policy documents (RAG sources)
-- -----------------------------------------------------------------------------
CREATE TABLE knowledge_documents (
    document_id UUID         NOT NULL,
    filename    VARCHAR(255) NOT NULL,
    title       VARCHAR(255) NOT NULL,
    mime_type   VARCHAR(120) NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    chunk_count INT          NOT NULL DEFAULT 0,
    status      VARCHAR(20)  NOT NULL,
    uploaded_by VARCHAR(64),
    uploaded_at TIMESTAMP    NOT NULL,
    error       TEXT,
    CONSTRAINT pk_knowledge_documents PRIMARY KEY (document_id),
    CONSTRAINT ck_knowledge_documents_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'INDEXED', 'FAILED'))
);

CREATE INDEX idx_knowledge_documents_uploaded_at ON knowledge_documents (uploaded_at DESC);

-- -----------------------------------------------------------------------------
-- document_chunks - Spring AI PgVectorStore table.
--
-- Column names and types are fixed by PgVectorStore (id / content / metadata /
-- embedding) - do not rename them. Flyway owns this table, so the store must be
-- configured with initialize-schema: false and table-name: document_chunks.
-- The embedding dimension matches Qwen3-Embedding-4B (2560).
-- No JPA entity maps this table; the VectorStore owns it.
-- -----------------------------------------------------------------------------
CREATE TABLE document_chunks (
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(2560)
);

COMMENT ON COLUMN document_chunks.metadata IS 'document_id, filename, title, section_title, chunk_index.';

-- HNSW cosine index. pgvector refuses to index a `vector` column with more than
-- 2000 dimensions ("column cannot have more than 2000 dimensions for hnsw
-- index"), and Qwen3-Embedding-4B produces 2560. The documented pgvector answer
-- at this dimensionality is to index a halfvec cast, which supports up to 4000
-- dimensions; the stored column stays a full-precision vector(2560).
CREATE INDEX idx_document_chunks_embedding_hnsw
    ON document_chunks USING hnsw ((embedding::halfvec(2560)) halfvec_cosine_ops);

-- Lets the knowledge service delete/count the chunks of one document cheaply.
CREATE INDEX idx_document_chunks_document_id
    ON document_chunks ((metadata ->> 'document_id'));
