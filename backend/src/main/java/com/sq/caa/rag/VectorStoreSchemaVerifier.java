package com.sq.caa.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Checks at startup that {@code document_chunks} is a table Spring AI's {@code PgVectorStore} can
 * actually use.
 *
 * <p>Flyway owns the table, so the store runs with {@code initialize-schema: false} and does not
 * create it. Spring AI's own {@code PgVectorSchemaValidator} is package private and is skipped
 * entirely when {@code schema-validation} is off, which would leave a schema drift - a renamed
 * column, or an embedding dimension that no longer matches the model - to surface as an opaque SQL
 * error on the first upload, long after deployment.
 *
 * <p>This component applies exactly the checks {@code PgVectorSchemaValidator.validateTableSchema}
 * performs, verified against the 2.0.1 bytecode:
 * <ol>
 *   <li>the table exists in the configured schema;</li>
 *   <li>it has the four columns the store hard-codes: {@code id}, {@code content},
 *       {@code metadata}, {@code embedding};</li>
 *   <li>the {@code embedding} column's declared dimension, read from {@code pg_attribute.atttypmod},
 *       equals {@code spring.ai.vectorstore.pgvector.dimensions}.</li>
 * </ol>
 *
 * <p>A mismatch aborts startup with a message naming the exact problem, which is the same failure
 * mode as the built-in validator but arrives before the first request instead of during it.
 *
 * <p>It also reports, once, whether an approximate-nearest-neighbour index is reachable by the
 * query {@code PgVectorStore} issues. At the configured 2,560 dimensions there is none - pgvector
 * refuses {@code hnsw} and {@code ivfflat} on a {@code vector} column above 2,000 dimensions, and
 * an index on a cast expression cannot serve a query that filters the raw column. That is a real
 * property of the deployment rather than a defect, and stating it at startup is what stops the next
 * maintainer from re-adding an index that looks like an optimisation and is not. See
 * {@code V4__rag_fixes.sql}.
 */
@Component
public class VectorStoreSchemaVerifier implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreSchemaVerifier.class);

    /** Column names are fixed by PgVectorStore's SQL; they cannot be configured. */
    private static final List<String> REQUIRED_COLUMNS =
            List.of("id", "content", "metadata", "embedding");

    private static final String COLUMNS_SQL = """
            SELECT column_name
              FROM information_schema.columns
             WHERE table_schema = ? AND table_name = ?
            """;

    /** Any index whose definition mentions the embedding column, however it is expressed. */
    private static final String EMBEDDING_INDEX_SQL = """
            SELECT indexname
              FROM pg_indexes
             WHERE schemaname = ? AND tablename = ? AND indexdef LIKE '%embedding%'
            """;

    private static final String EMBEDDING_DIMENSION_SQL = """
            SELECT a.atttypmod
              FROM pg_attribute a
              JOIN pg_class c     ON a.attrelid = c.oid
              JOIN pg_namespace n ON c.relnamespace = n.oid
             WHERE n.nspname = ? AND c.relname = ? AND a.attname = 'embedding'
               AND a.attnum > 0 AND NOT a.attisdropped
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RagProperties properties;
    private final String schemaName;
    private final String tableName;
    private final com.sq.caa.llm.LlmSettingsProvider llmSettings;

    public VectorStoreSchemaVerifier(JdbcTemplate jdbcTemplate,
            RagProperties properties,
            @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName,
            com.sq.caa.llm.LlmSettingsProvider llmSettings) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.llmSettings = llmSettings;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.verifyVectorSchema()) {
            log.info("Vector store schema verification disabled (caa.rag.verify-vector-schema=false)");
            return;
        }
        verify();
    }

    /** Runs the checks. Package visible so a test can call it directly. */
    void verify() {
        // The expected dimension is the runtime one: the admin LLM settings re-create the column
        // when the embedding model changes, and llm_settings.embed_dimension tracks that change
        // (no settings row = the environment default, which is the same source).
        int dimensions = llmSettings.effective().embedDimension();

        Set<String> actual = new LinkedHashSet<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(COLUMNS_SQL, schemaName, tableName)) {
            actual.add(String.valueOf(row.get("column_name")).toLowerCase(Locale.ROOT));
        }
        if (actual.isEmpty()) {
            throw new IllegalStateException("Vector store table " + qualifiedName()
                    + " does not exist. Flyway owns it - check that db/migration ran.");
        }

        List<String> missing = REQUIRED_COLUMNS.stream().filter(column -> !actual.contains(column))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Vector store table " + qualifiedName()
                    + " is missing the column(s) " + missing + " required by Spring AI's "
                    + "PgVectorStore. Expected: id uuid, content text, metadata json, embedding "
                    + "vector(" + dimensions + ").");
        }

        Integer declaredDimension =
                jdbcTemplate.queryForObject(EMBEDDING_DIMENSION_SQL, Integer.class, schemaName, tableName);
        if (declaredDimension == null || declaredDimension != dimensions) {
            throw new IllegalStateException("Vector store table " + qualifiedName()
                    + " declares embedding vector(" + declaredDimension + ") but the effective "
                    + "embedding configuration expects " + dimensions
                    + ". Re-save the embedding model in the admin LLM settings so the column is "
                    + "rebuilt at the model's dimension.");
        }

        log.info("PgVectorStore schema verified: {} (id, content, metadata, embedding vector({}))",
                qualifiedName(), dimensions);
        reportVectorIndex(dimensions);
    }

    /**
     * Says out loud whether knowledge search is index-assisted or an exact scan, so the answer is
     * in the log rather than in someone's assumption.
     */
    private void reportVectorIndex(int dimensions) {
        List<String> indexes;
        try {
            indexes = jdbcTemplate.queryForList(EMBEDDING_INDEX_SQL, String.class, schemaName,
                    tableName);
        } catch (RuntimeException e) {
            log.debug("Could not inspect the indexes on {}", qualifiedName(), e);
            return;
        }
        if (indexes.isEmpty()) {
            log.info("Knowledge search over {} is an exact nearest-neighbour scan: pgvector "
                    + "supports no ANN index on a vector({}) column, and PgVectorStore orders by "
                    + "the raw column so an index on a cast could not be used. Results are exact; "
                    + "cost grows linearly with the corpus. See V4__rag_fixes.sql.",
                    qualifiedName(), dimensions);
        } else {
            log.info("Vector index(es) present on {}: {}", qualifiedName(), indexes);
        }
    }

    private String qualifiedName() {
        return schemaName + "." + tableName;
    }
}
