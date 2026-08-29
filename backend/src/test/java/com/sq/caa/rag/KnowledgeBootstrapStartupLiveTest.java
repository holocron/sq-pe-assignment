package com.sq.caa.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.DocumentStatus;
import com.sq.caa.domain.KnowledgeDocument;
import com.sq.caa.repository.KnowledgeDocumentRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ContextConfiguration;

/**
 * The claim the whole RAG feature rests on, proved through the container rather than by calling the
 * seeding method: <b>boot the application against an empty knowledge base and it ends up with a
 * searchable policy corpus, without the boot waiting for it.</b>
 *
 * <p>The knowledge base is emptied by an {@link ApplicationContextInitializer}, which runs before
 * the context refreshes and therefore before {@code ApplicationReadyEvent} is published. What
 * happens next is entirely the application's own doing: {@link KnowledgeBootstrap#seedOnStartup()}
 * is invoked by the container, on the container's schedule, exactly as it would be under
 * {@code java -jar}.
 *
 * <p>Tagged {@code live}: it needs the embedding model. Run with
 * {@code mvn test -Dtest=KnowledgeBootstrapStartupLiveTest -Dtest.excludedGroups=}.
 */
@SpringBootTest
@ContextConfiguration(initializers = KnowledgeBootstrapStartupLiveTest.EmptyTheKnowledgeBase.class)
@Tag("live")
class KnowledgeBootstrapStartupLiveTest {

    /** Generous: three documents embed in about ten seconds against the local router. */
    private static final Duration SEEDING_TIMEOUT = Duration.ofMinutes(2);

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @Autowired
    private RagService ragService;

    @Test
    @DisplayName("starting the application seeds the corpus on a background thread")
    void startupSeedsTheCorpusWithoutBlockingTheBoot() {
        // The application is already up and answering - this test method is running - while the
        // seeding is still going. Had it been done on the startup thread, the corpus would
        // necessarily be complete by now.
        assertThat(seedingStillInFlight())
                .as("the application must be ready before the embedding round trips finish")
                .isTrue();

        List<KnowledgeDocument> seeded = awaitSeeding();

        assertThat(seeded).hasSize(3).allSatisfy(document -> {
            assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
            assertThat(document.getChunkCount()).isPositive();
            assertThat(document.getUploadedBy()).isEqualTo("system");
        });
        assertThat(ragService.searchPolicy("reporting threshold for large payments", 3))
                .as("the corpus the operator screen and the agent both read")
                .isNotEmpty();
    }

    /* ------------------------------------------------------------------ */

    private boolean seedingStillInFlight() {
        boolean threadAlive = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> "knowledge-bootstrap".equals(thread.getName())
                        && thread.isAlive());
        return threadAlive || indexed().size() < 3;
    }

    private List<KnowledgeDocument> awaitSeeding() {
        Instant deadline = Instant.now().plus(SEEDING_TIMEOUT);
        List<KnowledgeDocument> indexed = indexed();
        while (indexed.size() < 3 && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            indexed = indexed();
        }
        return indexed;
    }

    private List<KnowledgeDocument> indexed() {
        return documentRepository.findByStatusOrderByUploadedAtDesc(DocumentStatus.INDEXED);
    }

    /**
     * Clears the knowledge base before the context refreshes, so the seeding gate sees the empty
     * corpus a fresh deployment would have. Uses a plain JDBC connection because no bean exists
     * this early.
     */
    static class EmptyTheKnowledgeBase
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            Environment environment = context.getEnvironment();
            String url = environment.getProperty("spring.datasource.url",
                    "jdbc:postgresql://localhost:5432/caa");
            String user = environment.getProperty("spring.datasource.username", "caa");
            String password = environment.getProperty("spring.datasource.password", "caa");
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM document_chunks");
                statement.execute("DELETE FROM knowledge_documents");
            } catch (SQLException e) {
                throw new IllegalStateException("Could not empty the knowledge base before the "
                        + "context starts; the migrations must have run at least once.", e);
            }
        }
    }
}
