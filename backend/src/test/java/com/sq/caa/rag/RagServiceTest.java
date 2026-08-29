package com.sq.caa.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sq.caa.domain.DocumentStatus;
import com.sq.caa.domain.KnowledgeDocument;
import com.sq.caa.repository.KnowledgeDocumentRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingestion bookkeeping and retrieval, against the real repository and the real parsers.
 *
 * <p>The vector store is faked - embedding is a network call to a local model and has nothing to
 * say about whether a failed upload is recorded correctly - but everything else is the production
 * path: real Postgres, real POI and PDFBox, real chunker.
 */
@SpringBootTest
@Transactional
class RagServiceTest {

    @Autowired
    private RagService ragService;

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @Autowired
    private InMemoryChunkStore chunkStore;

    @BeforeEach
    void startFromAnEmptyKnowledgeBase() {
        chunkStore.reset();
        // Rolled back with the test transaction; keeps these assertions independent of seed data.
        documentRepository.deleteAllInBatch();
    }

    /* ------------------------------------------------------------------ */
    /* Ingestion                                                           */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("a .docx is parsed into sections, embedded, and recorded as INDEXED")
    void indexesADocx() {
        KnowledgeDocument document = ragService.ingest("aml-policy.docx",
                RagDocumentFixtures.styledDocx(), "admin");

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(document.getTitle()).isEqualTo("AML Transaction Monitoring Policy");
        assertThat(document.getMimeType()).isEqualTo(KnowledgeFormat.DOCX.mimeType());
        assertThat(document.getUploadedBy()).isEqualTo("admin");
        assertThat(document.getError()).isNull();
        assertThat(document.getChunkCount()).isEqualTo(3);
        assertThat(chunkStore.chunksOf(document.getDocumentId())).hasSize(3);
        assertThat(chunkStore.chunksOf(document.getDocumentId()))
                .extracting(chunk -> chunk.get("section_title"))
                .containsExactly("AML Transaction Monitoring Policy", "Reporting thresholds",
                        "Sanctioned jurisdictions");
        assertThat(documentRepository.findById(document.getDocumentId())).isPresent();
    }

    @Test
    @DisplayName("a .pdf goes down the same path, with headings found typographically")
    void indexesAPdf() {
        KnowledgeDocument document = ragService.ingest("crypto-policy.pdf",
                RagDocumentFixtures.typographicPdf(), "admin");

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(document.getMimeType()).isEqualTo(KnowledgeFormat.PDF.mimeType());
        assertThat(document.getChunkCount()).isEqualTo(2);
        assertThat(chunkStore.chunksOf(document.getDocumentId()))
                .extracting(chunk -> chunk.get("section_title"))
                .containsExactly("Privacy coins and mixers", "Unnamed exchanges");
    }

    @Test
    @DisplayName("anything that is not a Word or PDF document is refused before a row is created")
    void refusesOtherFormatsWithoutRecordingThem() {
        byte[] text = "Not a document at all.".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ragService.ingest("notes.pdf", text, "admin"))
                .isInstanceOf(UnsupportedDocumentException.class);

        assertThat(documentRepository.findByFilenameIgnoreCase("notes.pdf")).isEmpty();
        assertThat(chunkStore.size()).isZero();
    }

    @Test
    @DisplayName("a browser-supplied path is stripped from the stored file name")
    void stripsPathsFromFilenames() {
        KnowledgeDocument document = ragService.ingest("C:\\policies\\aml-policy.docx",
                RagDocumentFixtures.styledDocx(), "admin");

        assertThat(document.getFilename()).isEqualTo("aml-policy.docx");
    }

    @Test
    @DisplayName("uploading the same file name twice is refused, naming the document to delete")
    void refusesADuplicateFilename() {
        KnowledgeDocument first = ragService.ingest("aml-policy.docx",
                RagDocumentFixtures.styledDocx(), "admin");

        assertThatThrownBy(() -> ragService.ingest("aml-policy.docx",
                RagDocumentFixtures.styledDocx(), "admin"))
                .isInstanceOf(DuplicateDocumentException.class)
                .hasMessageContaining("already indexed");

        assertThat(chunkStore.chunksOf(first.getDocumentId())).hasSize(3);
    }

    @Test
    @DisplayName("a failed upload does not block the retry: it is replaced")
    void replacesAPreviouslyFailedUpload() {
        KnowledgeDocument failed = documentRepository.saveAndFlush(KnowledgeDocument.builder()
                .documentId(UUID.randomUUID())
                .filename("aml-policy.docx")
                .title("Aml policy")
                .mimeType(KnowledgeFormat.DOCX.mimeType())
                .sizeBytes(10)
                .chunkCount(0)
                .status(DocumentStatus.FAILED)
                .uploadedBy("admin")
                .uploadedAt(Instant.now())
                .error("the model was down")
                .build());

        KnowledgeDocument retried = ragService.ingest("aml-policy.docx",
                RagDocumentFixtures.styledDocx(), "admin");

        assertThat(retried.getDocumentId()).isNotEqualTo(failed.getDocumentId());
        assertThat(retried.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(documentRepository.findById(failed.getDocumentId())).isEmpty();
    }

    @Test
    @DisplayName("a real .pdf with no text leaves a FAILED row carrying the reason, and no chunks")
    void recordsAFailedExtraction() {
        assertThatThrownBy(() -> ragService.ingest("scan.pdf",
                RagDocumentFixtures.textlessPdf(), "admin"))
                .isInstanceOf(DocumentExtractionException.class);

        KnowledgeDocument recorded = documentRepository.findByFilenameIgnoreCase("scan.pdf")
                .orElseThrow();
        assertThat(recorded.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(recorded.getChunkCount()).isZero();
        assertThat(recorded.getError()).contains("OCR");
        assertThat(chunkStore.size()).isZero();
    }

    @Test
    @DisplayName("chunks written before an embedding failure are rolled back out of the store")
    void purgesPartialChunksWhenEmbeddingFails() {
        chunkStore.failAfter(2);

        assertThatThrownBy(() -> ragService.ingest("aml-policy.docx",
                RagDocumentFixtures.styledDocx(), "admin"))
                .isInstanceOf(KnowledgeIndexException.class);

        KnowledgeDocument recorded = documentRepository.findByFilenameIgnoreCase("aml-policy.docx")
                .orElseThrow();
        assertThat(recorded.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(recorded.getError()).isNotBlank();
        assertThat(chunkStore.size()).isZero();
    }

    /* ------------------------------------------------------------------ */
    /* Deletion                                                            */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("deleting a document removes its chunks from the vector store too")
    void deleteRemovesTheChunks() {
        KnowledgeDocument document = ragService.ingest("aml-policy.docx",
                RagDocumentFixtures.styledDocx(), "admin");
        assertThat(chunkStore.size()).isEqualTo(3);

        ragService.delete(document.getDocumentId());

        assertThat(documentRepository.findById(document.getDocumentId())).isEmpty();
        assertThat(chunkStore.size()).isZero();
    }

    @Test
    @DisplayName("deleting an unknown document is a 404, not a silent success")
    void deleteRejectsAnUnknownDocument() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> ragService.delete(unknown))
                .isInstanceOf(KnowledgeDocumentNotFoundException.class);
    }

    /* ------------------------------------------------------------------ */
    /* Search                                                              */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("a blank query never reaches the embedding model")
    void blankQueriesShortCircuit() {
        assertThat(ragService.search("   ", 5)).isEmpty();
        assertThat(ragService.search(null, 5)).isEmpty();
        assertThat(chunkStore.searches).isEmpty();
    }

    @Test
    @DisplayName("an empty corpus answers from the document table instead of embedding the query")
    void emptyCorpusShortCircuits() {
        assertThat(ragService.search("reporting threshold", 5)).isEmpty();
        assertThat(chunkStore.searches).isEmpty();
    }

    @Test
    @DisplayName("topK is clamped: zero means the default, and the maximum is enforced")
    void clampsTopK() {
        ragService.ingest("aml-policy.docx", RagDocumentFixtures.styledDocx(), "admin");

        ragService.search("thresholds", 0);
        ragService.search("thresholds", 1_000);
        ragService.search("thresholds", 3);

        assertThat(chunkStore.searches).containsExactly(5, 25, 3);
    }

    @Test
    @DisplayName("hits come back with the provenance a citation needs")
    void returnsProvenanceWithEveryHit() {
        KnowledgeDocument document = ragService.ingest("aml-policy.docx",
                RagDocumentFixtures.styledDocx(), "admin");

        List<RetrievedChunk> hits = ragService.search("reporting threshold", 2);

        assertThat(hits).isNotEmpty().allSatisfy(hit -> {
            assertThat(hit.documentId()).isEqualTo(document.getDocumentId());
            assertThat(hit.filename()).isEqualTo("aml-policy.docx");
            assertThat(hit.sectionTitle()).isNotBlank();
            assertThat(hit.content()).isNotBlank();
            assertThat(hit.citation()).startsWith("aml-policy.docx > ");
        });
    }

    /* ------------------------------------------------------------------ */
    /* Fake vector store                                                   */
    /* ------------------------------------------------------------------ */

    @TestConfiguration
    static class FakeChunkStoreConfiguration {

        @Bean
        @Primary
        InMemoryChunkStore inMemoryChunkStore() {
            return new InMemoryChunkStore();
        }
    }

    /**
     * A chunk store that keeps everything in a list. Retrieval is a keyword overlap score rather
     * than a vector distance, which is enough to assert that provenance survives the round trip.
     */
    static class InMemoryChunkStore implements ChunkStore {

        private final List<Map<String, Object>> chunks = new ArrayList<>();
        private final List<Integer> searches = new ArrayList<>();
        private int failAfterChunks = Integer.MAX_VALUE;

        void reset() {
            chunks.clear();
            searches.clear();
            failAfterChunks = Integer.MAX_VALUE;
        }

        void failAfter(int writtenChunks) {
            this.failAfterChunks = writtenChunks;
        }

        int size() {
            return chunks.size();
        }

        List<Map<String, Object>> chunksOf(UUID documentId) {
            return chunks.stream()
                    .filter(chunk -> documentId.toString().equals(chunk.get("document_id")))
                    .toList();
        }

        @Override
        public int index(UUID documentId, String filename, String title, List<TextChunk> textChunks) {
            for (TextChunk textChunk : textChunks) {
                if (chunks.size() >= failAfterChunks) {
                    throw new KnowledgeIndexException("the embedding model went away");
                }
                Map<String, Object> chunk = new LinkedHashMap<>();
                chunk.put("document_id", documentId.toString());
                chunk.put("filename", filename);
                chunk.put("title", title);
                chunk.put("section_title", textChunk.sectionTitle().isBlank()
                        ? title : textChunk.sectionTitle());
                chunk.put("chunk_index", textChunk.chunkIndex());
                chunk.put("content", textChunk.content());
                chunks.add(chunk);
            }
            return textChunks.size();
        }

        @Override
        public void deleteByDocument(UUID documentId) {
            chunks.removeIf(chunk -> documentId.toString().equals(chunk.get("document_id")));
        }

        @Override
        public List<RetrievedChunk> search(String query, int topK) {
            searches.add(topK);
            return chunks.stream()
                    .map(chunk -> new RetrievedChunk(
                            UUID.randomUUID().toString(),
                            UUID.fromString((String) chunk.get("document_id")),
                            (String) chunk.get("filename"),
                            (String) chunk.get("title"),
                            (String) chunk.get("section_title"),
                            (Integer) chunk.get("chunk_index"),
                            (String) chunk.get("content"),
                            overlap(query, (String) chunk.get("content"))))
                    .sorted((left, right) -> Double.compare(right.score(), left.score()))
                    .limit(topK)
                    .toList();
        }

        private static double overlap(String query, String content) {
            String haystack = content.toLowerCase();
            long matched = query.toLowerCase().lines()
                    .flatMap(line -> List.of(line.split("\\s+")).stream())
                    .filter(word -> word.length() > 3 && haystack.contains(word))
                    .count();
            return Math.min(1d, matched / 4d);
        }
    }
}
