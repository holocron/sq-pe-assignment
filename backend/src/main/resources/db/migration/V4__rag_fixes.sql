-- =============================================================================
-- V4 - knowledge-base index and constraint corrections
--
-- Three defects in the V2 knowledge-base schema, all of the same kind: an index
-- or a guarantee that the code does not actually get. Each is resolved below
-- with the reason it was wrong, because the wrong versions were written for
-- plausible reasons and would otherwise be written again.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. The HNSW vector index is dead weight and is dropped. Knowledge search is an
--    exact scan by design at this embedding dimensionality.
--
-- V2 created:
--     CREATE INDEX idx_document_chunks_embedding_hnsw
--         ON document_chunks USING hnsw ((embedding::halfvec(2560)) halfvec_cosine_ops);
--
-- The comment there was half right. pgvector really does refuse an ANN index on
-- a `vector` column wider than 2000 dimensions, and Qwen3-Embedding-4B produces
-- 2560. Verified on this instance (PostgreSQL 17.11 / pgvector 0.8.6):
--
--     CREATE INDEX ... USING hnsw    (embedding vector_cosine_ops)
--         -> ERROR: column cannot have more than 2000 dimensions for hnsw index
--     CREATE INDEX ... USING ivfflat (embedding vector_cosine_ops)
--         -> ERROR: column cannot have more than 2000 dimensions for ivfflat index
--
-- What the comment got wrong is the conclusion. Indexing a halfvec cast does not
-- give this application an ANN index, because the query is not ours to shape.
-- Spring AI 2.0.1's PgVectorStore hard-codes its SQL (PgVectorStore$PgDistanceType,
-- COSINE_DISTANCE):
--
--     SELECT *, embedding <=> ? AS distance FROM document_chunks
--      WHERE embedding <=> ? < ? ORDER BY distance LIMIT ?
--
-- Both operators are applied to the raw `embedding` column. PostgreSQL matches an
-- expression index only against the identical expression, so `embedding <=> $1`
-- can never use an index built on `embedding::halfvec(2560) <=> $1`. Confirmed
-- with EXPLAIN and enable_seqscan = off: the Spring AI query form plans as
-- Limit -> Sort -> Seq Scan, while the same query rewritten to ORDER BY the
-- halfvec cast plans as an Index Scan on the halfvec index. The store is used
-- unmodified (VectorStoreConfig declares no VectorStore bean of its own), so the
-- rewritten form is never issued.
--
-- The index was therefore never read, while every ingest paid HNSW graph
-- maintenance for it - and, worse, it documented an optimisation that does not
-- exist, so nobody scaling the corpus had a reason to look here.
--
-- Options considered and rejected:
--   * hnsw / ivfflat on `embedding` directly - impossible above 2000 dimensions.
--   * Storing `embedding` as halfvec(2560) so an index would apply - PgVectorStore
--     binds a `vector` parameter, `halfvec <=> vector` has no operator, and
--     BUILD_SPEC section 2 fixes the column as VECTOR(2560).
--   * A binary-quantised bit index - another expression index the query cannot use.
--   * Subclassing PgVectorStore to rewrite the SQL - a fork of a third-party
--     query template maintained against decompiled bytecode, to speed up an exact
--     scan over a corpus of a few hundred rows. Not a trade worth making here.
--
-- The honest state is written down instead: knowledge search is an exact
-- nearest-neighbour scan. That is exact rather than approximate, costs about a
-- millisecond over this corpus, and scales linearly. A corpus large enough for
-- that to hurt (tens of thousands of chunks) needs an embedding model of 2000
-- dimensions or fewer, at which point `CREATE INDEX ... USING hnsw (embedding
-- vector_cosine_ops)` becomes available and the Spring AI query will use it with
-- no code change.
-- -----------------------------------------------------------------------------
DROP INDEX IF EXISTS idx_document_chunks_embedding_hnsw;


-- -----------------------------------------------------------------------------
-- 2. Replace the metadata index with one the queries can actually use.
--
-- V2 created a btree index on the ->> expression:
--     CREATE INDEX idx_document_chunks_document_id
--         ON document_chunks ((metadata ->> 'document_id'));
--
-- but no query in the application is written that way. Every read and delete of a
-- document's chunks goes through Spring AI's filter API, and
-- PgVectorFilterExpressionConverter renders a filter as a jsonpath match against a
-- jsonb cast of the column:
--
--     metadata::jsonb @@ '$.document_id == "..."'::jsonpath                     (delete)
--     metadata::jsonb @@ '($.document_id == "a" || $.document_id == "b")'::jsonpath (search)
--
-- A btree on `metadata ->> 'document_id'` cannot serve @@ on `metadata::jsonb`,
-- so both planned as sequential scans. A GIN index with jsonb_path_ops on the
-- same cast the query uses does serve them; verified on this instance over 20,000
-- rows, where both statements above plan as
-- Bitmap Heap Scan -> Bitmap Index Scan on the index below.
--
-- This matters more after V4 than before it: RagService now restricts every
-- search to the INDEXED documents, so the IN form above runs on every knowledge
-- search and on every search_policy_knowledge tool call.
-- -----------------------------------------------------------------------------
DROP INDEX IF EXISTS idx_document_chunks_document_id;

CREATE INDEX idx_document_chunks_metadata
    ON document_chunks USING gin ((metadata::jsonb) jsonb_path_ops);

COMMENT ON INDEX idx_document_chunks_metadata IS
    'Serves the jsonpath predicates Spring AI issues: metadata::jsonb @@ ''$.document_id == "..."''.';


-- -----------------------------------------------------------------------------
-- 3. Back the "one document per file name" rule with a constraint.
--
-- RagService refuses an upload whose file name is already taken and answers 409.
-- That was a read-then-write with nothing behind it: two concurrent uploads of the
-- same name both saw an empty table and both inserted. From that moment
-- findByFilenameIgnoreCase (which returns Optional) raised
-- IncorrectResultSizeDataAccessException on every later upload of that name, which
-- no handler maps, so the documented 409 became a permanent 500 that only a manual
-- DELETE could clear.
--
-- The unique index makes the rule true rather than hoped for; RagService catches
-- the violation and still answers 409, so the loser of a race gets the documented
-- response. lower(filename) matches the case-insensitive lookup the service does.
--
-- The de-duplication below is a repair for databases created before this
-- migration. It keeps the most recently uploaded row for each file name and
-- removes the older duplicates together with their chunks, so the unique index can
-- be created without failing the migration and without leaving orphaned vectors
-- behind. On a database that never had duplicates - the expected case, since the
-- service-level check catches everything but a true race - it deletes nothing.
-- -----------------------------------------------------------------------------
WITH ranked AS (
    SELECT document_id,
           row_number() OVER (PARTITION BY lower(filename)
                              ORDER BY uploaded_at DESC, document_id DESC) AS rn
      FROM knowledge_documents
),
superseded AS (
    SELECT document_id FROM ranked WHERE rn > 1
)
DELETE FROM document_chunks
 WHERE (metadata ->> 'document_id') IN (SELECT document_id::text FROM superseded);

WITH ranked AS (
    SELECT document_id,
           row_number() OVER (PARTITION BY lower(filename)
                              ORDER BY uploaded_at DESC, document_id DESC) AS rn
      FROM knowledge_documents
)
DELETE FROM knowledge_documents
 WHERE document_id IN (SELECT document_id FROM ranked WHERE rn > 1);

CREATE UNIQUE INDEX uq_knowledge_documents_filename
    ON knowledge_documents (lower(filename));

COMMENT ON INDEX uq_knowledge_documents_filename IS
    'Enforces the 409 duplicate-upload contract; the service check alone is a non-atomic read-then-write.';
