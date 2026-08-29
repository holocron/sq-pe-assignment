package com.sq.caa.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.DocumentStatus;
import com.sq.caa.domain.KnowledgeDocument;
import com.sq.caa.rag.KnowledgeBootstrap.BootstrapReport;
import com.sq.caa.repository.KnowledgeDocumentRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end seeding against the real embedding model, the real pgvector store and the real
 * database.
 *
 * <p>Tagged {@code live} and therefore excluded from the default build: it needs the lemonade
 * router on {@code http://localhost:13305/api/v1} to be up. Run it with
 * {@code mvn test -Dtest=KnowledgeBootstrapLiveTest -Dtest.excludedGroups=}.
 *
 * <p><b>It writes to the database on purpose.</b> This is the one test that proves the claim the
 * whole RAG feature rests on - that a fresh deployment ends up with a searchable policy corpus -
 * and that cannot be proved without embedding real text and reading it back through pgvector. It
 * clears the knowledge base first, so it leaves exactly the state a fresh deployment would have.
 * Nothing outside {@code knowledge_documents} and {@code document_chunks} is touched.
 *
 * <p>The application's own bootstrap listener is switched off for this context so the assertions
 * drive the seeding rather than racing the startup thread.
 */
@SpringBootTest(properties = "caa.knowledge.bootstrap.enabled=false")
@Tag("live")
class KnowledgeBootstrapLiveTest {

    @Autowired
    private RagService ragService;

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void emptyTheKnowledgeBase() {
        for (KnowledgeDocument document : documentRepository.findAllByOrderByUploadedAtDesc()) {
            ragService.delete(document.getDocumentId());
        }
        documentRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM document_chunks");
        assertThat(chunkRows()).isZero();
    }

    @Test
    @DisplayName("a fresh deployment ends up with a searchable policy corpus")
    void seedsARealCorpusThatTheAgentCanCite() {
        BootstrapReport report = bootstrap().seed();

        assertThat(report.attempted()).isTrue();
        assertThat(report.indexedCount()).isEqualTo(3);
        assertThat(report.documents()).allSatisfy(outcome -> {
            assertThat(outcome.status()).isEqualTo(DocumentStatus.INDEXED);
            assertThat(outcome.chunkCount()).isPositive();
        });

        // Every chunk was really embedded and really written to pgvector.
        assertThat(chunkRows()).isEqualTo(report.chunkCount());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunks WHERE embedding IS NULL", Integer.class))
                .isZero();

        // And retrieval really answers the questions the operator screen advertises and the agent
        // is told to ask, through the one shared entry point.
        List<RetrievedChunk> thresholds =
                ragService.searchPolicy("reporting threshold for large payments", 3);
        assertThat(thresholds).hasSize(3);
        assertThat(thresholds.get(0).score()).isGreaterThan(0.3d);
        assertThat(thresholds).allSatisfy(hit -> {
            assertThat(hit.filename()).isNotBlank();
            assertThat(hit.sectionTitle()).isNotBlank();
            assertThat(hit.content()).isNotBlank();
            assertThat(hit.content().length()).isLessThanOrEqualTo(RagService.MAX_PASSAGE_CHARS);
        });
        assertThat(ragService.searchPolicy("sanctioned jurisdictions list", 3)).isNotEmpty();
        assertThat(ragService.searchPolicy("crypto mixer and privacy coin exposure", 3))
                .isNotEmpty();
    }

    @Test
    @DisplayName("restarting does not embed the corpus a second time")
    void restartingIsANoOp() {
        bootstrap().seed();
        int chunksAfterSeeding = chunkRows();
        List<String> idsAfterSeeding = chunkIds();

        BootstrapReport second = bootstrap().seed();

        assertThat(second.attempted()).isFalse();
        assertThat(chunkRows()).isEqualTo(chunksAfterSeeding);
        assertThat(chunkIds()).isEqualTo(idsAfterSeeding);
    }

    /* ------------------------------------------------------------------ */

    private KnowledgeBootstrap bootstrap() {
        return new KnowledgeBootstrap(ragService, documentRepository,
                new KnowledgeBootstrapProperties(true, "classpath*:/knowledge/*", "system"),
                new PathMatchingResourcePatternResolver());
    }

    private int chunkRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM document_chunks",
                Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> chunkIds() {
        return jdbcTemplate.queryForList("SELECT id::text FROM document_chunks ORDER BY id",
                String.class);
    }
}
