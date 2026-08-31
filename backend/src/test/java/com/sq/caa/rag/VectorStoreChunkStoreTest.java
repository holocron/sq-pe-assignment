package com.sq.caa.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * The contract between the application and Spring AI: what ends up in a chunk's metadata, how
 * chunks are addressed, and how a hit is read back.
 *
 * <p>Hand-written fakes rather than a live store, so these run without Postgres or the model.
 */
class VectorStoreChunkStoreTest {

    private static final int DIMENSIONS = 2560;
    private static final UUID DOCUMENT_ID = UUID.fromString("6d5e5f60-1111-4222-8333-444444444444");

    private final RecordingVectorStore vectorStore = new RecordingVectorStore();
    private final FixedDimensionEmbeddingModel embeddingModel =
            new FixedDimensionEmbeddingModel(DIMENSIONS);
    private final VectorStoreChunkStore chunkStore = new VectorStoreChunkStore(vectorStore,
            embeddingModel, properties(4), settingsProvider(DIMENSIONS));

    @Test
    @DisplayName("every chunk carries the metadata the spec fixes, and its section heading")
    void writesTheRequiredMetadata() {
        int stored = chunkStore.index(DOCUMENT_ID, "aml.docx", "AML Policy",
                List.of(chunk(0, 0, "2. Thresholds", "Report payments of 10,000 USD or more.")));

        assertThat(stored).isEqualTo(1);
        assertThat(vectorStore.added).hasSize(1);
        Document document = vectorStore.added.get(0);
        assertThat(document.getMetadata())
                .containsEntry("document_id", DOCUMENT_ID.toString())
                .containsEntry("filename", "aml.docx")
                .containsEntry("title", "AML Policy")
                .containsEntry("section_title", "2. Thresholds")
                .containsEntry("chunk_index", 0)
                .containsEntry("section_index", 0);
        assertThat(document.getText()).contains("10,000 USD");
    }

    @Test
    @DisplayName("chunk ids are UUIDs derived from the document and index, so re-indexing updates")
    void chunkIdsAreDeterministicUuids() {
        chunkStore.index(DOCUMENT_ID, "aml.docx", "AML Policy",
                List.of(chunk(0, 0, "One", "First."), chunk(1, 1, "Two", "Second.")));
        List<String> firstPass = vectorStore.added.stream().map(Document::getId).toList();

        vectorStore.added.clear();
        chunkStore.index(DOCUMENT_ID, "aml.docx", "AML Policy",
                List.of(chunk(0, 0, "One", "First."), chunk(1, 1, "Two", "Second.")));

        assertThat(vectorStore.added.stream().map(Document::getId).toList())
                .isEqualTo(firstPass);
        assertThat(firstPass).allSatisfy(id -> UUID.fromString(id));
        assertThat(firstPass.get(0)).isNotEqualTo(firstPass.get(1));
    }

    @Test
    @DisplayName("chunks are written in batches of caa.rag.embed-batch-size")
    void writesInBatches() {
        List<TextChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            chunks.add(chunk(i, i, "Section " + i, "Body of section " + i + "."));
        }

        assertThat(chunkStore.index(DOCUMENT_ID, "aml.docx", "AML Policy", chunks)).isEqualTo(9);
        assertThat(vectorStore.batchSizes).containsExactly(4, 4, 1);
    }

    @Test
    @DisplayName("a chunk with no heading of its own falls back to the document title")
    void sectionTitleFallsBackToTheDocumentTitle() {
        chunkStore.index(DOCUMENT_ID, "aml.docx", "AML Policy",
                List.of(chunk(0, 0, "  ", "Preamble text.")));

        assertThat(vectorStore.added.get(0).getMetadata())
                .containsEntry("section_title", "AML Policy");
    }

    @Test
    @DisplayName("deletion filters on document_id, which is what unlinks a document from the corpus")
    void deletesByDocumentId() {
        chunkStore.deleteByDocument(DOCUMENT_ID);

        assertThat(vectorStore.deleteFilters).hasSize(1);
        Filter.Expression filter = vectorStore.deleteFilters.get(0);
        assertThat(filter.type()).isEqualTo(Filter.ExpressionType.EQ);
        assertThat(filter.left()).isEqualTo(new Filter.Key("document_id"));
        assertThat(filter.right()).isEqualTo(new Filter.Value(DOCUMENT_ID.toString()));
    }

    @Test
    @DisplayName("a hit is flattened into its provenance, its text and a rounded score")
    void mapsHitsToRetrievedChunks() {
        vectorStore.hits = List.of(Document.builder()
                .id("2f0f1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b")
                .text("Report payments of 10,000 USD or more.")
                .metadata(java.util.Map.of(
                        "document_id", DOCUMENT_ID.toString(),
                        "filename", "aml.docx",
                        "title", "AML Policy",
                        "section_title", "2. Thresholds",
                        "chunk_index", 3))
                .score(0.812345678d)
                .build());

        List<RetrievedChunk> chunks =
                chunkStore.search("reporting threshold", 5, List.of(DOCUMENT_ID));

        assertThat(chunks).hasSize(1);
        RetrievedChunk chunk = chunks.get(0);
        assertThat(chunk.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(chunk.filename()).isEqualTo("aml.docx");
        assertThat(chunk.sectionTitle()).isEqualTo("2. Thresholds");
        assertThat(chunk.chunkIndex()).isEqualTo(3);
        assertThat(chunk.score()).isEqualTo(0.8123d);
        assertThat(chunk.citation()).isEqualTo("aml.docx > 2. Thresholds");
        assertThat(vectorStore.lastRequest.getTopK()).isEqualTo(5);
        assertThat(vectorStore.lastRequest.getQuery()).isEqualTo("reporting threshold");
    }

    @Test
    @DisplayName("search is restricted to the given documents, inside the query rather than after it")
    void searchFiltersOnTheOwningDocuments() {
        UUID other = UUID.fromString("11111111-2222-4333-8444-555555555555");

        chunkStore.search("reporting threshold", 5, List.of(DOCUMENT_ID, other));

        Filter.Expression filter = vectorStore.lastRequest.getFilterExpression();
        assertThat(filter)
                .as("without a filter expression the query ranks over every row in the table, "
                        + "including chunks of PROCESSING and FAILED documents")
                .isNotNull();
        assertThat(filter.type()).isEqualTo(Filter.ExpressionType.IN);
        assertThat(filter.left()).isEqualTo(new Filter.Key("document_id"));
        assertThat(filter.right()).isEqualTo(
                new Filter.Value(List.of(DOCUMENT_ID.toString(), other.toString())));
    }

    @Test
    @DisplayName("a search with no searchable document never reaches the embedding model")
    void searchWithoutAnyDocumentShortCircuits() {
        assertThat(chunkStore.search("reporting threshold", 5, List.of())).isEmpty();

        assertThat(vectorStore.lastRequest).isNull();
    }

    @Test
    @DisplayName("a model whose vectors do not fit the column is refused before the insert fails")
    void refusesAMismatchedEmbeddingModel() {
        VectorStoreChunkStore mismatched = new VectorStoreChunkStore(vectorStore,
                new FixedDimensionEmbeddingModel(1536), properties(4), settingsProvider(DIMENSIONS));

        assertThatThrownBy(() -> mismatched.index(DOCUMENT_ID, "aml.docx", "AML Policy",
                List.of(chunk(0, 0, "One", "First."))))
                .isInstanceOf(KnowledgeIndexException.class)
                .hasMessageContaining("1536-dimensional")
                .hasMessageContaining("vector(2560)");
        assertThat(vectorStore.added).isEmpty();
    }

    @Test
    @DisplayName("a store failure surfaces as a retryable knowledge-base fault")
    void wrapsStoreFailures() {
        vectorStore.failOnAdd = true;

        assertThatThrownBy(() -> chunkStore.index(DOCUMENT_ID, "aml.docx", "AML Policy",
                List.of(chunk(0, 0, "One", "First."))))
                .isInstanceOf(KnowledgeIndexException.class)
                .hasMessageContaining("aml.docx");
    }

    @Test
    @DisplayName("a runtime embedding-model change re-verifies against the new dimension")
    void followsRuntimeDimensionChanges() {
        java.util.concurrent.atomic.AtomicInteger dimension =
                new java.util.concurrent.atomic.AtomicInteger(1536);
        VectorStoreChunkStore store = new VectorStoreChunkStore(vectorStore,
                embeddingModel, properties(4),
                () -> new com.sq.caa.llm.EffectiveLlmSettings("http://localhost", "chat-model",
                        "embed-model", dimension.get(), "", "", "test", null, null));

        // First configuration expects 1536 but the model serves 2560: refused.
        assertThatThrownBy(() -> store.index(DOCUMENT_ID, "aml.docx", "AML Policy",
                List.of(chunk(0, 0, "One", "First."))))
                .isInstanceOf(KnowledgeIndexException.class);

        // After the admin re-saves the embedding model, the column and the recorded dimension
        // move to 2560 and the same store accepts the write.
        dimension.set(DIMENSIONS);
        int stored = store.index(DOCUMENT_ID, "aml.docx", "AML Policy",
                List.of(chunk(0, 0, "One", "First.")));
        assertThat(stored).isEqualTo(1);
    }

    /* ------------------------------------------------------------------ */

    private static com.sq.caa.llm.LlmSettingsProvider settingsProvider(int dimensions) {
        return () -> new com.sq.caa.llm.EffectiveLlmSettings("http://localhost", "chat-model",
                "embed-model", dimensions, "", "", "test", null, null);
    }

    private static RagProperties properties(int batchSize) {
        return new RagProperties(800, 100, 5, 25, 0.0, batchSize, 20_971_520L, false);
    }

    private static TextChunk chunk(int index, int section, String title, String body) {
        String content = title.isBlank() ? body : title + "\n\n" + body;
        return new TextChunk(index, section, title, 0, 1, content, TokenEstimator.estimate(content));
    }

    /** Records what the store was asked to do; returns whatever hits the test planted. */
    private static final class RecordingVectorStore implements VectorStore {

        private final List<Document> added = new ArrayList<>();
        private final List<Integer> batchSizes = new ArrayList<>();
        private final List<Filter.Expression> deleteFilters = new ArrayList<>();
        private List<Document> hits = List.of();
        private SearchRequest lastRequest;
        private boolean failOnAdd;

        @Override
        public void add(List<Document> documents) {
            if (failOnAdd) {
                throw new IllegalStateException("connection refused");
            }
            batchSizes.add(documents.size());
            added.addAll(documents);
        }

        @Override
        public void delete(List<String> ids) {
            throw new UnsupportedOperationException("deletion is always by document filter");
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            deleteFilters.add(filterExpression);
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            this.lastRequest = request;
            return hits;
        }
    }

    /** An embedding model that only ever answers the dimension question. */
    private record FixedDimensionEmbeddingModel(int dimensions) implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("the vector store does the embedding");
        }

        @Override
        public float[] embed(Document document) {
            throw new UnsupportedOperationException("the vector store does the embedding");
        }
    }
}
