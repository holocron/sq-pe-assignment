package com.sq.caa.rag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link ChunkStore} backed by Spring AI's pgvector store.
 *
 * <p>Writing a chunk goes through {@link VectorStore#add(List)}, which embeds every document with
 * the auto-configured {@link EmbeddingModel} (Qwen3-Embedding-4B behind the local router) and
 * writes {@code id, content, metadata, embedding} into {@code document_chunks} in one batch
 * statement. Doing the embedding through the store rather than by hand keeps the write atomic per
 * batch and reuses Spring AI's token-aware batching, which is what keeps a large document from
 * being sent to the model as a single oversized request.
 *
 * <p><b>Deterministic chunk ids.</b> The store is configured with {@code id-type: uuid}, so a
 * chunk id must parse as a UUID. Rather than a random one, the id is derived from
 * {@code documentId:chunkIndex}, which makes re-indexing the same document idempotent: the
 * {@code ON CONFLICT (id) DO UPDATE} in the store's insert overwrites the previous revision of a
 * chunk instead of leaving a duplicate behind.
 *
 * <p><b>Dimension guard.</b> The embedding model is a GGUF build served by a router that can be
 * pointed at a different model by configuration. If that model's vectors are not
 * {@code spring.ai.vectorstore.pgvector.dimensions} long, PostgreSQL rejects the insert with an
 * opaque type error. The first write therefore asks the model for its dimensionality (Spring AI
 * caches the answer) and fails with a message that names both numbers.
 */
@Component
public class VectorStoreChunkStore implements ChunkStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreChunkStore.class);

    /** Scale used when rounding similarity scores; four decimals is well past what is meaningful. */
    private static final double SCORE_SCALE = 10_000d;

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final RagProperties properties;
    private final int configuredDimensions;

    private volatile boolean dimensionsVerified;

    public VectorStoreChunkStore(VectorStore vectorStore,
            EmbeddingModel embeddingModel,
            RagProperties properties,
            @Value("${spring.ai.vectorstore.pgvector.dimensions:1536}") int configuredDimensions) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.configuredDimensions = configuredDimensions;
    }

    /* ------------------------------------------------------------------ */
    /* Writing                                                             */
    /* ------------------------------------------------------------------ */

    @Override
    public int index(UUID documentId, String filename, String title, List<TextChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        verifyEmbeddingDimensions();

        List<Document> documents = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            documents.add(toDocument(documentId, filename, title, chunk));
        }

        int stored = 0;
        int batchSize = Math.max(1, properties.embedBatchSize());
        for (int from = 0; from < documents.size(); from += batchSize) {
            List<Document> batch = documents.subList(from, Math.min(from + batchSize, documents.size()));
            try {
                vectorStore.add(batch);
            } catch (RuntimeException e) {
                throw new KnowledgeIndexException("Failed to embed and store chunks " + from + ".."
                        + (from + batch.size() - 1) + " of '" + filename + "': " + rootMessage(e), e);
            }
            stored += batch.size();
            log.debug("Indexed {}/{} chunk(s) of '{}'", stored, documents.size(), filename);
        }
        return stored;
    }

    @Override
    public void deleteByDocument(UUID documentId) {
        Filter.Expression owner = new FilterExpressionBuilder()
                .eq(ChunkMetadata.DOCUMENT_ID, documentId.toString())
                .build();
        try {
            vectorStore.delete(owner);
        } catch (RuntimeException e) {
            throw new KnowledgeIndexException("Failed to delete the chunks of document " + documentId
                    + ": " + rootMessage(e), e);
        }
        log.debug("Deleted the chunks of document {}", documentId);
    }

    /* ------------------------------------------------------------------ */
    /* Reading                                                             */
    /* ------------------------------------------------------------------ */

    @Override
    public List<RetrievedChunk> search(String query, int topK, Collection<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(properties.similarityThreshold())
                .filterExpression(ownedBy(documentIds))
                .build();
        List<Document> hits;
        try {
            hits = vectorStore.similaritySearch(request);
        } catch (RuntimeException e) {
            throw new KnowledgeIndexException(
                    "The knowledge base could not be searched: " + rootMessage(e), e);
        }
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> chunks = new ArrayList<>(hits.size());
        for (Document hit : hits) {
            chunks.add(toRetrievedChunk(hit));
        }
        return List.copyOf(chunks);
    }

    /**
     * {@code document_id IN (...)}.
     *
     * <p>Spring AI's {@code PgVectorFilterExpressionConverter} renders this as
     * {@code metadata::jsonb @@ '($.document_id == "a" || $.document_id == "b")'::jsonpath}, which
     * the GIN {@code jsonb_path_ops} index added in {@code V4__rag_fixes.sql} can serve.
     */
    private static Filter.Expression ownedBy(Collection<UUID> documentIds) {
        Object[] ids = documentIds.stream().map(String::valueOf).toArray();
        return new FilterExpressionBuilder().in(ChunkMetadata.DOCUMENT_ID, ids).build();
    }

    /* ------------------------------------------------------------------ */
    /* Mapping                                                             */
    /* ------------------------------------------------------------------ */

    private static Document toDocument(UUID documentId, String filename, String title,
            TextChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChunkMetadata.DOCUMENT_ID, documentId.toString());
        metadata.put(ChunkMetadata.FILENAME, filename);
        metadata.put(ChunkMetadata.TITLE, title);
        metadata.put(ChunkMetadata.SECTION_TITLE, sectionTitleOf(chunk, title));
        metadata.put(ChunkMetadata.CHUNK_INDEX, chunk.chunkIndex());
        metadata.put(ChunkMetadata.SECTION_INDEX, chunk.sectionIndex());
        return Document.builder()
                .id(chunkId(documentId, chunk.chunkIndex()))
                .text(chunk.content())
                .metadata(metadata)
                .build();
    }

    /**
     * Stable id for a chunk: a name-based UUID over {@code documentId:chunkIndex}. The store's id
     * column is a {@code uuid}, so a readable key is not an option, but a deterministic one still
     * makes re-indexing an update rather than an insert.
     */
    static String chunkId(UUID documentId, int chunkIndex) {
        String name = documentId + ":" + chunkIndex;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sectionTitleOf(TextChunk chunk, String documentTitle) {
        String section = chunk.sectionTitle();
        return section == null || section.isBlank() ? documentTitle : section;
    }

    private static RetrievedChunk toRetrievedChunk(Document hit) {
        Map<String, Object> metadata = hit.getMetadata() == null ? Map.of() : hit.getMetadata();
        return new RetrievedChunk(
                hit.getId(),
                asUuid(metadata.get(ChunkMetadata.DOCUMENT_ID)),
                asText(metadata.get(ChunkMetadata.FILENAME)),
                asText(metadata.get(ChunkMetadata.TITLE)),
                asText(metadata.get(ChunkMetadata.SECTION_TITLE)),
                asInt(metadata.get(ChunkMetadata.CHUNK_INDEX)),
                hit.getText() == null ? "" : hit.getText(),
                roundedScore(hit.getScore()));
    }

    /**
     * pgvector returns cosine <em>distance</em>; Spring AI turns it into {@code 1 - distance}, a
     * similarity where 1 is identical. It arrives as a {@code float} widened to {@code double}, so
     * it is rounded to four decimals to keep the API output free of float noise.
     */
    private static double roundedScore(Double score) {
        if (score == null) {
            return 0d;
        }
        return Math.round(score * SCORE_SCALE) / SCORE_SCALE;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static UUID asUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            log.warn("Chunk metadata carries a non-UUID {}: {}", ChunkMetadata.DOCUMENT_ID, value);
            return null;
        }
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Guards                                                              */
    /* ------------------------------------------------------------------ */

    private void verifyEmbeddingDimensions() {
        if (dimensionsVerified) {
            return;
        }
        int actual;
        try {
            // Spring AI caches this after the first call, so the probe costs one embedding per JVM.
            actual = embeddingModel.dimensions();
        } catch (RuntimeException e) {
            throw new KnowledgeIndexException("The embedding model could not be reached, so the "
                    + "document cannot be indexed: " + rootMessage(e), e);
        }
        if (actual != configuredDimensions) {
            throw new KnowledgeIndexException("The embedding model returns " + actual
                    + "-dimensional vectors but document_chunks.embedding is vector("
                    + configuredDimensions + "). Point spring.ai.openai.embedding.options.model at "
                    + "the right model, or align spring.ai.vectorstore.pgvector.dimensions and the "
                    + "Flyway migration.");
        }
        dimensionsVerified = true;
        log.info("Embedding model verified at {} dimensions", actual);
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
