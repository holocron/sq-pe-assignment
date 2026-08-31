package com.sq.caa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sq.caa.domain.DocumentStatus;
import com.sq.caa.domain.KnowledgeDocument;
import com.sq.caa.rag.KnowledgeIndexException;
import com.sq.caa.rag.RagService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The background re-embed job: one run at a time, per-document failures counted instead of
 * aborting, and the failure reason of a document without stored bytes surfaced as lastError.
 */
class ReembedServiceTest {

    private static KnowledgeDocument document(String filename) {
        return KnowledgeDocument.builder()
                .documentId(UUID.randomUUID())
                .filename(filename)
                .title(filename)
                .mimeType("application/pdf")
                .sizeBytes(10)
                .status(DocumentStatus.INDEXED)
                .uploadedAt(Instant.now())
                .build();
    }

    private static ReembedStatus awaitCompletion(ReembedService service) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        ReembedStatus status;
        do {
            status = service.status();
            Thread.sleep(10);
        } while (status.running() && System.nanoTime() < deadline);
        return status;
    }

    @Test
    @DisplayName("every document is re-indexed; one failure does not abort the run")
    void perDocumentFailuresAreCounted() throws Exception {
        RagService ragService = mock(RagService.class);
        KnowledgeDocument good = document("good.pdf");
        KnowledgeDocument legacy = document("legacy.pdf");
        KnowledgeDocument alsoGood = document("also-good.docx");
        when(ragService.listDocuments()).thenReturn(List.of(good, legacy, alsoGood));
        doThrow(new KnowledgeIndexException("The original file bytes of 'legacy.pdf' were not "
                + "stored (it predates V7), so it cannot be re-embedded."))
                .when(ragService).reindex(legacy.getDocumentId());

        ReembedStatus status;
        try (ReembedService service = new ReembedService(ragService)) {
            assertThat(service.start()).isTrue();
            status = awaitCompletion(service);
        }

        assertThat(status.running()).isFalse();
        assertThat(status.totalDocuments()).isEqualTo(3);
        assertThat(status.completedDocuments()).isEqualTo(2);
        assertThat(status.failedDocuments()).isEqualTo(1);
        assertThat(status.lastError()).contains("legacy.pdf").contains("not stored");
        verify(ragService, times(3)).reindex(any());
    }

    @Test
    @DisplayName("a second start while a run is in flight is refused")
    void onlyOneRunAtATime() throws Exception {
        RagService ragService = mock(RagService.class);
        when(ragService.listDocuments()).thenAnswer(invocation -> {
            Thread.sleep(200);
            return List.of();
        });

        try (ReembedService service = new ReembedService(ragService)) {
            assertThat(service.start()).isTrue();
            assertThat(service.start()).as("already running").isFalse();
            assertThat(service.status().running()).isTrue();
            awaitCompletion(service);
        }
    }
}
