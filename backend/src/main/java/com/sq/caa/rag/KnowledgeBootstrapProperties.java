package com.sq.caa.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code caa.knowledge.bootstrap.*} - the one-time seeding of the policy corpus.
 *
 * <p>BUILD_SPEC section 6 requires the sample policy documents to be present "so RAG returns
 * something", and unlike every other seed item they cannot be plain SQL: a chunk is only useful
 * once it has been embedded by the live model. {@link KnowledgeBootstrap} therefore performs the
 * seeding, and these properties are how a deployment turns it off or points it somewhere else.
 *
 * @param enabled    whether an empty knowledge base is seeded at startup; set to {@code false} in a
 *                   deployment whose corpus is curated by hand, or where re-seeding after the
 *                   administrator has deleted every document would be surprising
 * @param location   Spring resource pattern the documents are read from. The default resolves
 *                   inside the application artifact, so it works identically whether the jar is
 *                   started from the project root or from {@code /opt/caa}. It may be pointed at
 *                   the file system instead - {@code file:/etc/caa/policies/*} - to seed a
 *                   deployment from documents that are not shipped with the build.
 * @param uploadedBy value recorded in {@code knowledge_documents.uploaded_by}, so a seeded document
 *                   is distinguishable from one an administrator uploaded
 */
@ConfigurationProperties(prefix = "caa.knowledge.bootstrap")
public record KnowledgeBootstrapProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("classpath*:/knowledge/*") String location,
        @DefaultValue("system") String uploadedBy) {

    /** {@code knowledge_documents.uploaded_by} is VARCHAR(64). */
    private static final int MAX_UPLOADED_BY_LENGTH = 64;

    public KnowledgeBootstrapProperties {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("caa.knowledge.bootstrap.location must not be blank");
        }
        if (uploadedBy == null || uploadedBy.isBlank()) {
            throw new IllegalArgumentException(
                    "caa.knowledge.bootstrap.uploaded-by must not be blank");
        }
        if (uploadedBy.length() > MAX_UPLOADED_BY_LENGTH) {
            throw new IllegalArgumentException("caa.knowledge.bootstrap.uploaded-by must be at most "
                    + MAX_UPLOADED_BY_LENGTH + " characters");
        }
    }
}
