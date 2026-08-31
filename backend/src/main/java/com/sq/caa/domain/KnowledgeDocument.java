package com.sq.caa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An uploaded knowledge-base document. Maps {@code knowledge_documents}.
 *
 * <p>The embedded chunks themselves live in {@code document_chunks}, which is owned by Spring AI's
 * {@code PgVectorStore}; they are linked back through the {@code document_id} metadata key.
 */
@Entity
@Table(name = "knowledge_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeDocument {

    /** Metadata key that links a {@code document_chunks} row back to this document. */
    public static final String METADATA_DOCUMENT_ID = "document_id";

    @Id
    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "mime_type", nullable = false, length = 120)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DocumentStatus status;

    @Column(name = "uploaded_by", length = 64)
    private String uploadedBy;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    /** Failure detail when {@link #status} is {@link DocumentStatus#FAILED}. */
    @Column(name = "error", columnDefinition = "text")
    private String error;

    /**
     * The original upload bytes, kept so an embedding-model change can re-extract, re-chunk and
     * re-embed the document without asking the administrator to upload it again. Null for documents
     * uploaded before the column was added (V7).
     */
    @Column(name = "source_bytes")
    private byte[] sourceBytes;
}
