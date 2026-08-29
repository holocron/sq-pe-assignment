package com.sq.caa.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.DocumentStatus;
import com.sq.caa.domain.KnowledgeDocument;
import com.sq.caa.repository.KnowledgeDocumentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * The other half of the startup contract: the application must come up normally when the embedding
 * model is not there.
 *
 * <p>Points the OpenAI base URL at a closed port and boots against an empty knowledge base. The
 * context must reach {@code ApplicationReadyEvent}, the seeding must give up with a warning, and
 * the knowledge base must be left empty rather than half-written - with {@code FAILED} rows so the
 * next start retries instead of concluding the corpus was curated.
 *
 * <p>Tagged {@code live} only because it shares the fixture and the database with its sibling; it
 * deliberately does <em>not</em> need the model to be up.
 */
@SpringBootTest(properties = "spring.ai.openai.base-url=http://127.0.0.1:9/v1")
@ContextConfiguration(initializers = KnowledgeBootstrapStartupLiveTest.EmptyTheKnowledgeBase.class)
@Tag("live")
class KnowledgeBootstrapModelDownLiveTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(1);

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @Autowired
    private RagService ragService;

    @AfterEach
    void clearTheFailedRows() {
        documentRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("an unreachable embedding model leaves the corpus empty, not the application down")
    void startsUpWithTheModelDown() {
        // Reaching this line at all is the primary assertion: the context refreshed and the
        // application became ready with the model unreachable.
        List<KnowledgeDocument> recorded = awaitAttempt();

        assertThat(recorded)
                .as("the attempt must be recorded, so an operator can see why the base is empty")
                .isNotEmpty()
                .allSatisfy(document -> {
                    assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
                    assertThat(document.getChunkCount()).isZero();
                    assertThat(document.getError()).isNotBlank();
                });
        assertThat(documentRepository.findByStatusOrderByUploadedAtDesc(DocumentStatus.INDEXED))
                .isEmpty();

        // Search degrades to "nothing found" rather than to an error the operator cannot act on.
        assertThat(ragService.searchPolicy("reporting threshold", 3)).isEmpty();
    }

    /** Waits until the seeding thread has finished with every row it created. */
    private List<KnowledgeDocument> awaitAttempt() {
        Instant deadline = Instant.now().plus(TIMEOUT);
        List<KnowledgeDocument> recorded = documentRepository.findAllByOrderByUploadedAtDesc();
        while (!settled(recorded) && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            recorded = documentRepository.findAllByOrderByUploadedAtDesc();
        }
        return recorded;
    }

    private static boolean settled(List<KnowledgeDocument> documents) {
        return !documents.isEmpty() && documents.stream().noneMatch(document ->
                document.getStatus() == DocumentStatus.PENDING
                        || document.getStatus() == DocumentStatus.PROCESSING);
    }
}
