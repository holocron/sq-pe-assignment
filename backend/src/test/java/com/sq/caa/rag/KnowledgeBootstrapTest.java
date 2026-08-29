package com.sq.caa.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.DocumentStatus;
import com.sq.caa.domain.KnowledgeDocument;
import com.sq.caa.rag.KnowledgeBootstrap.BootstrapReport;
import com.sq.caa.repository.KnowledgeDocumentRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeding of an empty knowledge base: the gate that makes it run once, and the failure modes that
 * must not take the application down with them.
 *
 * <p>Runs the real {@link RagService} against the real database and the real parsers, with the
 * embedding step faked - the point of these assertions is the bookkeeping around ingestion, not the
 * vectors. {@link KnowledgeBootstrapLiveTest} covers the real model.
 *
 * <p>The application's own bootstrap listener is disabled for this context so it cannot race the
 * assertions; the component under test is constructed explicitly and driven synchronously.
 */
@SpringBootTest(properties = "caa.knowledge.bootstrap.enabled=false")
@Transactional
class KnowledgeBootstrapTest {

    private static final String LOCATION = "classpath*:/knowledge/*";

    @Autowired
    private RagService ragService;

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @Autowired
    private InMemoryChunkStore chunkStore;

    @BeforeEach
    void startFromAnEmptyKnowledgeBase() {
        chunkStore.reset();
        // Rolled back with the test transaction.
        documentRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("an empty knowledge base is seeded from the documents shipped inside the jar")
    void seedsAnEmptyKnowledgeBase() {
        BootstrapReport report = bootstrap(LOCATION, true).seed();

        assertThat(report.attempted()).isTrue();
        assertThat(report.documents()).hasSize(3);
        assertThat(report.documents()).extracting(KnowledgeBootstrap.DocumentOutcome::filename)
                .containsExactly("AML-Thresholds-and-Structuring-Policy.docx",
                        "Cryptocurrency-and-Virtual-Asset-Risk-Policy.docx",
                        "Sanctions-and-High-Risk-Jurisdictions-Policy.pdf");
        assertThat(report.indexedCount()).isEqualTo(3);
        assertThat(report.chunkCount()).isGreaterThan(10);

        List<KnowledgeDocument> stored = documentRepository.findAllByOrderByUploadedAtDesc();
        assertThat(stored).hasSize(3).allSatisfy(document -> {
            assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
            assertThat(document.getChunkCount()).isPositive();
            assertThat(document.getUploadedBy()).isEqualTo("system");
            assertThat(document.getTitle()).isNotBlank();
            assertThat(document.getError()).isNull();
        });
        assertThat(chunkStore.size()).isEqualTo(report.chunkCount());

        // The seeded corpus is immediately searchable, which is the whole point of seeding it.
        assertThat(ragService.search("reporting threshold for large payments", 3)).isNotEmpty();
    }

    @Test
    @DisplayName("a restart does not seed again: a second run is a no-op")
    void isIdempotentAcrossRestarts() {
        KnowledgeBootstrap bootstrap = bootstrap(LOCATION, true);
        BootstrapReport first = bootstrap.seed();
        int chunksAfterFirstRun = chunkStore.size();

        BootstrapReport second = bootstrap.seed();

        assertThat(first.attempted()).isTrue();
        assertThat(second.attempted()).isFalse();
        assertThat(second.reason()).contains("already holds");
        assertThat(documentRepository.findAllByOrderByUploadedAtDesc()).hasSize(3);
        assertThat(chunkStore.size()).isEqualTo(chunksAfterFirstRun);
    }

    @Test
    @DisplayName("a corpus an administrator already curated is left alone")
    void doesNotTouchACuratedCorpus() {
        documentRepository.saveAndFlush(KnowledgeDocument.builder()
                .documentId(UUID.randomUUID())
                .filename("house-policy.docx")
                .title("House policy")
                .mimeType(KnowledgeFormat.DOCX.mimeType())
                .sizeBytes(10)
                .chunkCount(4)
                .status(DocumentStatus.INDEXED)
                .uploadedBy("admin")
                .uploadedAt(Instant.now())
                .build());

        BootstrapReport report = bootstrap(LOCATION, true).seed();

        assertThat(report.attempted()).isFalse();
        assertThat(documentRepository.findAllByOrderByUploadedAtDesc()).hasSize(1);
        assertThat(chunkStore.size()).isZero();
    }

    @Test
    @DisplayName("an unreachable embedding model leaves the knowledge base empty, not the app dead")
    void survivesAnUnreachableEmbeddingModel() {
        chunkStore.failEveryIndexWith(() -> new KnowledgeIndexException(
                "The embedding model could not be reached, so the document cannot be indexed: "
                        + "Connection refused"));

        BootstrapReport report = bootstrap(LOCATION, true).seed();

        assertThat(report.attempted()).isTrue();
        assertThat(report.reason()).contains("embedding model was unreachable");
        assertThat(report.indexedCount()).isZero();
        // Stopped at the first document rather than failing all three against a model that is down.
        assertThat(report.documents()).hasSize(1);
        assertThat(report.documents().get(0).modelUnavailable()).isTrue();
        assertThat(chunkStore.size()).isZero();
        // And nothing escapes: the caller sees a report, never an exception.
        assertThat(documentRepository.findAllByOrderByUploadedAtDesc())
                .allSatisfy(document ->
                        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED));
    }

    @Test
    @DisplayName("a run that only left FAILED rows is retried on the next start")
    void retriesAfterAFailedRun() {
        chunkStore.failEveryIndexWith(() -> new KnowledgeIndexException("model down"));
        bootstrap(LOCATION, true).seed();
        assertThat(documentRepository.findAllByOrderByUploadedAtDesc())
                .isNotEmpty()
                .allSatisfy(document ->
                        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED));

        chunkStore.failEveryIndexWith(null);
        BootstrapReport retried = bootstrap(LOCATION, true).seed();

        assertThat(retried.attempted())
                .as("FAILED rows must not count as a curated corpus, or the knowledge base "
                        + "stays empty forever")
                .isTrue();
        assertThat(retried.indexedCount()).isEqualTo(3);
        assertThat(documentRepository.findAllByOrderByUploadedAtDesc()).hasSize(3)
                .allSatisfy(document ->
                        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED));
    }

    @Test
    @DisplayName("seeding can be switched off with caa.knowledge.bootstrap.enabled")
    void canBeSwitchedOff() {
        BootstrapReport report = bootstrap(LOCATION, false).seed();

        assertThat(report.attempted()).isFalse();
        assertThat(report.reason()).contains("caa.knowledge.bootstrap.enabled");
        assertThat(documentRepository.findAllByOrderByUploadedAtDesc()).isEmpty();
    }

    @Test
    @DisplayName("a location with nothing in it warns instead of failing")
    void toleratesAnEmptyLocation() {
        BootstrapReport report = bootstrap("classpath*:/no-such-directory/*", true).seed();

        assertThat(report.attempted()).isFalse();
        assertThat(report.reason()).contains("no documents matched");
        assertThat(documentRepository.findAllByOrderByUploadedAtDesc()).isEmpty();
    }

    @Test
    @DisplayName("files that are not policy documents are ignored rather than recorded as failures")
    void ignoresFilesThatAreNotDocuments() {
        ResourcePatternResolver mixed = new StubResolver(() -> new Resource[] {
                namedResource("readme.txt", "not a policy"),
                namedResource("notes.md", "nor this"),
        });
        KnowledgeBootstrap bootstrap = new KnowledgeBootstrap(ragService, documentRepository,
                new KnowledgeBootstrapProperties(true, LOCATION, "system"), mixed);

        BootstrapReport report = bootstrap.seed();

        assertThat(report.attempted()).isFalse();
        assertThat(documentRepository.findAllByOrderByUploadedAtDesc()).isEmpty();
    }

    @Test
    @DisplayName("the startup hook never throws, whatever the seeding does")
    void startupHookSwallowsEverything() {
        ResourcePatternResolver exploding = new StubResolver(() -> {
            throw new UncheckedIOException(new IOException("the classpath is on fire"));
        });
        KnowledgeBootstrap bootstrap = new KnowledgeBootstrap(ragService, documentRepository,
                new KnowledgeBootstrapProperties(true, LOCATION, "system"), exploding);

        // The listener the container calls, on the container's thread: it must return normally.
        bootstrap.seedOnStartup();

        // And the synchronous entry point degrades to "nothing to seed" rather than propagating.
        assertThat(bootstrap.seed().attempted()).isFalse();
        assertThat(documentRepository.findAllByOrderByUploadedAtDesc()).isEmpty();
    }

    /* ------------------------------------------------------------------ */

    private KnowledgeBootstrap bootstrap(String location, boolean enabled) {
        return new KnowledgeBootstrap(ragService, documentRepository,
                new KnowledgeBootstrapProperties(enabled, location, "system"),
                new PathMatchingResourcePatternResolver());
    }

    /**
     * A resolver that answers {@code getResources} from a supplier. {@link ResourcePatternResolver}
     * extends {@link org.springframework.core.io.ResourceLoader}, so it is not a functional
     * interface and cannot be written as a lambda.
     */
    private record StubResolver(Supplier<Resource[]> resources) implements ResourcePatternResolver {

        @Override
        public Resource[] getResources(String locationPattern) {
            return resources.get();
        }

        @Override
        public Resource getResource(String location) {
            return new PathMatchingResourcePatternResolver().getResource(location);
        }

        @Override
        public ClassLoader getClassLoader() {
            return KnowledgeBootstrapTest.class.getClassLoader();
        }
    }

    private static Resource namedResource(String filename, String content) {
        return new ByteArrayResource(content.getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    @TestConfiguration
    static class FakeChunkStoreConfiguration {

        @Bean
        @Primary
        InMemoryChunkStore inMemoryChunkStore() {
            return new InMemoryChunkStore();
        }
    }
}
