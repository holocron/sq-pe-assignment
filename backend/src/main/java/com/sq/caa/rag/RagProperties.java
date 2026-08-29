package com.sq.caa.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code caa.rag.*}.
 *
 * <p>Every value has a default, so the knowledge base works out of the box with nothing added to
 * {@code application.yml}; the properties exist so chunk sizing and retrieval depth can be tuned
 * against a real corpus without a rebuild.
 *
 * @param chunkTargetTokens   target size of an embedded window, in estimated tokens
 * @param chunkOverlapTokens  tokens repeated between consecutive windows of one section
 * @param defaultTopK         hits returned when a search does not ask for a specific number
 * @param maxTopK             upper bound on {@code topK}, so one request cannot drain the table
 * @param similarityThreshold minimum cosine similarity a hit must reach, {@code 0..1}; the default
 *                            of 0 returns the nearest chunks whatever their score, which is the
 *                            right behaviour for a small curated policy corpus where the best
 *                            available answer is always worth showing
 * @param embedBatchSize      chunks sent to the embedding model per vector-store write
 * @param maxUploadBytes      largest accepted upload; the servlet limit is enforced separately
 * @param verifyVectorSchema  whether to check the {@code document_chunks} layout at startup
 */
@ConfigurationProperties(prefix = "caa.rag")
public record RagProperties(
        @DefaultValue("800") int chunkTargetTokens,
        @DefaultValue("100") int chunkOverlapTokens,
        @DefaultValue("5") int defaultTopK,
        @DefaultValue("25") int maxTopK,
        @DefaultValue("0.0") double similarityThreshold,
        @DefaultValue("16") int embedBatchSize,
        @DefaultValue("20971520") long maxUploadBytes,
        @DefaultValue("true") boolean verifyVectorSchema) {

    public RagProperties {
        if (chunkTargetTokens < 120) {
            throw new IllegalArgumentException("caa.rag.chunk-target-tokens must be at least 120");
        }
        if (chunkOverlapTokens < 0) {
            throw new IllegalArgumentException("caa.rag.chunk-overlap-tokens must not be negative");
        }
        if (defaultTopK < 1) {
            throw new IllegalArgumentException("caa.rag.default-top-k must be at least 1");
        }
        if (maxTopK < defaultTopK) {
            throw new IllegalArgumentException("caa.rag.max-top-k must be at least default-top-k");
        }
        if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
            throw new IllegalArgumentException("caa.rag.similarity-threshold must be within 0..1");
        }
        if (embedBatchSize < 1) {
            throw new IllegalArgumentException("caa.rag.embed-batch-size must be at least 1");
        }
        if (maxUploadBytes < 1) {
            throw new IllegalArgumentException("caa.rag.max-upload-bytes must be positive");
        }
    }
}
