package com.sq.caa.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sq.caa.domain.DocumentStatus;
import com.sq.caa.domain.KnowledgeDocument;
import com.sq.caa.repository.KnowledgeDocumentRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingestion bookkeeping and retrieval, against the real repository and the real parsers.
 *
 * <p>The vector store is faked - embedding is a network call to a local model and has nothing to
 * say about whether a failed upload is recorded correctly - but everything else is the production
 * path: real Postgres, real POI and PDFBox, real chunker.
 *
 * <p>Bootstrap seeding is switched off: it would otherwise commit documents from a background
 * thread while these transactional assertions run against an empty corpus.
 */
@SpringBootTest(properties = "caa.knowledge.bootstrap.enabled=false")
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
    @DisplayName("the duplicate-name rule is backed by a constraint, not just by a lookup")
    void duplicateFilenamesAreImpossibleAtTheDatabaseLevel() {
        ragService.ingest("aml-policy.docx", RagDocumentFixtures.styledDocx(), "admin");

        // Bypasses the service check the way a concurrent upload does: two callers both find no
        // existing row and both insert. Without the unique index on lower(filename) both rows
        // land, and every later upload of that name turns into a 500 instead of the documented
        // 409 because findByFilenameIgnoreCase then finds two.
        KnowledgeDocument racing = KnowledgeDocument.builder()
                .documentId(UUID.randomUUID())
                .filename("AML-Policy.DOCX")
                .title("Aml policy")
                .mimeType(KnowledgeFormat.DOCX.mimeType())
                .sizeBytes(10)
                .chunkCount(0)
                .status(DocumentStatus.PROCESSING)
                .uploadedBy("admin2")
                .uploadedAt(Instant.now())
                .build();

        assertThatThrownBy(() -> documentRepository.saveAndFlush(racing))
                .isInstanceOf(DataIntegrityViolationException.class);
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
        assertThat(chunkStore.searches()).isEmpty();
    }

    @Test
    @DisplayName("an empty corpus answers from the document table instead of embedding the query")
    void emptyCorpusShortCircuits() {
        assertThat(ragService.search("reporting threshold", 5)).isEmpty();
        assertThat(chunkStore.searches()).isEmpty();
    }

    @Test
    @DisplayName("topK is clamped: zero means the default, and the maximum is enforced")
    void clampsTopK() {
        ragService.ingest("aml-policy.docx", RagDocumentFixtures.styledDocx(), "admin");

        ragService.search("thresholds", 0);
        ragService.search("thresholds", 1_000);
        ragService.search("thresholds", 3);

        assertThat(chunkStore.searches()).containsExactly(5, 25, 3);
    }

    @Test
    @DisplayName("only INDEXED documents are searchable: a half-written upload cannot be cited")
    void chunksOfDocumentsThatAreNotIndexedAreNotRetrievable() {
        KnowledgeDocument indexed = ragService.ingest("aml-policy.docx",
                RagDocumentFixtures.styledDocx(), "admin");
        // A second document whose chunks are in the store while its row is still PROCESSING -
        // exactly the state ingestion passes through, since each batch commits on its own.
        KnowledgeDocument halfWritten = documentRepository.saveAndFlush(KnowledgeDocument.builder()
                .documentId(UUID.randomUUID())
                .filename("crypto-policy.docx")
                .title("Crypto policy")
                .mimeType(KnowledgeFormat.DOCX.mimeType())
                .sizeBytes(10)
                .chunkCount(0)
                .status(DocumentStatus.PROCESSING)
                .uploadedBy("admin")
                .uploadedAt(Instant.now())
                .build());
        chunkStore.index(halfWritten.getDocumentId(), "crypto-policy.docx", "Crypto policy",
                List.of(new TextChunk(0, 0, "3.2 Mixers", 0, 1,
                        "Mixing services are prohibited and every reporting threshold applies.",
                        12)));

        List<RetrievedChunk> hits = ragService.search("reporting threshold", 10);

        assertThat(hits).isNotEmpty();
        assertThat(hits).extracting(RetrievedChunk::documentId)
                .containsOnly(indexed.getDocumentId())
                .doesNotContain(halfWritten.getDocumentId());
        // The restriction is pushed into the query, not applied to the results afterwards.
        assertThat(chunkStore.searchedDocuments()).containsExactly(Set.of(indexed.getDocumentId()));

        // The same document once it FAILS and its compensating chunk delete did not get through.
        halfWritten.setStatus(DocumentStatus.FAILED);
        documentRepository.saveAndFlush(halfWritten);
        assertThat(ragService.search("reporting threshold", 10))
                .extracting(RetrievedChunk::documentId)
                .doesNotContain(halfWritten.getDocumentId());
    }

    @Test
    @DisplayName("passages are cut to one length for every caller, so the screen shows what the "
            + "agent read")
    void passagesAreCappedIdenticallyForEveryCaller() {
        KnowledgeDocument document = documentRepository.saveAndFlush(KnowledgeDocument.builder()
                .documentId(UUID.randomUUID())
                .filename("long-policy.docx")
                .title("Long policy")
                .mimeType(KnowledgeFormat.DOCX.mimeType())
                .sizeBytes(10)
                .chunkCount(1)
                .status(DocumentStatus.INDEXED)
                .uploadedBy("admin")
                .uploadedAt(Instant.now())
                .build());
        String oversized = "Threshold clause that the reviewing officer must apply. "
                .repeat(60);
        assertThat(oversized.length()).isGreaterThan(RagService.MAX_PASSAGE_CHARS);
        chunkStore.index(document.getDocumentId(), "long-policy.docx", "Long policy",
                List.of(new TextChunk(0, 0, "2. Thresholds", 0, 1, oversized, 400)));

        String uiContent = ragService.searchPolicy("threshold clause", 5).get(0).content();
        String agentContent = ragService.searchPolicy("threshold clause", 3).get(0).content();

        assertThat(uiContent).isEqualTo(agentContent);
        assertThat(uiContent).endsWith(RagService.TRUNCATION_MARKER);
        // Exactly the cap, including the marker, so a caller applying the same cap again is a
        // no-op and cannot stack a second marker on the end.
        assertThat(uiContent.length()).isLessThanOrEqualTo(RagService.MAX_PASSAGE_CHARS);
    }

    @Test
    @DisplayName("searchPolicy treats a null topK as the configured default")
    void searchPolicyDefaultsTopK() {
        ragService.ingest("aml-policy.docx", RagDocumentFixtures.styledDocx(), "admin");

        ragService.searchPolicy("thresholds", null);
        ragService.searchPolicy("thresholds", -4);

        assertThat(chunkStore.searches()).containsExactly(5, 5);
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
}
