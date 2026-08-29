package com.sq.caa.web.dto;

import com.sq.caa.domain.DocumentStatus;
import com.sq.caa.domain.KnowledgeDocument;
import com.sq.caa.rag.RetrievedChunk;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response payloads of the knowledge-base API. */
public final class KnowledgeDtos {

    private KnowledgeDtos() {
    }

    /**
     * An uploaded document as the admin screen sees it.
     *
     * @param chunkCount number of embedded windows; 0 until ingestion succeeds
     * @param error      why ingestion failed, null unless {@code status} is {@code FAILED}
     */
    public record KnowledgeDocumentDto(
            UUID documentId,
            String filename,
            String title,
            String mimeType,
            long sizeBytes,
            int chunkCount,
            DocumentStatus status,
            String uploadedBy,
            Instant uploadedAt,
            String error) {

        public static KnowledgeDocumentDto from(KnowledgeDocument document) {
            return new KnowledgeDocumentDto(document.getDocumentId(), document.getFilename(),
                    document.getTitle(), document.getMimeType(), document.getSizeBytes(),
                    document.getChunkCount(), document.getStatus(), document.getUploadedBy(),
                    document.getUploadedAt(), document.getError());
        }
    }

    /**
     * A knowledge-base query.
     *
     * @param topK how many passages to return; null falls back to {@code caa.rag.default-top-k},
     *             and anything above {@code caa.rag.max-top-k} is clamped to it
     */
    public record KnowledgeSearchRequest(
            @NotBlank @Size(max = 1000) String query,
            @Min(1) @Max(100) Integer topK) {

        /** {@code 0} means "use the configured default"; the service does the clamping. */
        public int topKOrDefault() {
            return topK == null ? 0 : topK;
        }
    }

    /**
     * One search hit.
     *
     * @param sectionTitle heading of the policy section the passage came from
     * @param filename     source document, so a finding can be cited
     * @param score        cosine similarity, 1 is identical
     * @param citation     ready-made {@code file > section} label for the UI and for prompts
     */
    public record KnowledgeChunkDto(
            String chunkId,
            UUID documentId,
            String filename,
            String title,
            String sectionTitle,
            int chunkIndex,
            String content,
            double score,
            String citation) {

        public static KnowledgeChunkDto from(RetrievedChunk chunk) {
            return new KnowledgeChunkDto(chunk.chunkId(), chunk.documentId(), chunk.filename(),
                    chunk.title(), chunk.sectionTitle(), chunk.chunkIndex(), chunk.content(),
                    chunk.score(), chunk.citation());
        }
    }
}
