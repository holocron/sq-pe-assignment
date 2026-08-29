package com.sq.caa.config;

import com.sq.caa.rag.RagProperties;
import com.sq.caa.rag.SectionChunker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the knowledge base.
 *
 * <p>The {@code VectorStore} itself is not declared here: Spring AI's pgvector auto-configuration
 * already builds a {@code PgVectorStore} from {@code spring.ai.vectorstore.pgvector.*} and the
 * auto-configured {@code EmbeddingModel}, and re-declaring it by hand would only risk drifting from
 * those properties. Flyway owns {@code document_chunks} (hence {@code initialize-schema: false});
 * {@code com.sq.caa.rag.VectorStoreSchemaVerifier} checks at startup that the table still matches
 * what the store expects.
 *
 * <p>What does need declaring is the chunker, because it is a pure function object rather than a
 * component scan target - building it here is what lets {@code caa.rag.chunk-*} tune window sizing
 * without a rebuild.
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class VectorStoreConfig {

    /** Section-aware chunker sized from {@code caa.rag.chunk-target-tokens} / {@code -overlap-tokens}. */
    @Bean
    public SectionChunker sectionChunker(RagProperties properties) {
        return new SectionChunker(properties.chunkTargetTokens(), properties.chunkOverlapTokens());
    }
}
