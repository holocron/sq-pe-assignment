package com.sq.caa.rag;

import java.util.List;
import java.util.UUID;

/**
 * Where embedded chunks live.
 *
 * <p>Everything Spring AI specific - embedding, the {@code Document} representation, pgvector's
 * similarity search and its metadata filters - sits behind this one interface, so
 * {@link RagService} deals only in the application's own types and stays testable without a model
 * or a database.
 */
public interface ChunkStore {

    /**
     * Embeds and stores the chunks of one document.
     *
     * <p>Implementations must be idempotent per {@code (documentId, chunkIndex)}: re-indexing the
     * same document replaces its chunks instead of duplicating them.
     *
     * @param documentId owning {@code knowledge_documents.document_id}
     * @param filename   source file name, written to every chunk's metadata
     * @param title      document title, written to every chunk's metadata
     * @param chunks     chunks in document order
     * @return the number of chunks stored
     * @throws KnowledgeIndexException when the embedding model or the store is unavailable
     */
    int index(UUID documentId, String filename, String title, List<TextChunk> chunks);

    /**
     * Removes every chunk belonging to a document. Deleting a document that has no chunks is not
     * an error.
     *
     * @throws KnowledgeIndexException when the store cannot be reached
     */
    void deleteByDocument(UUID documentId);

    /**
     * Nearest chunks to a query, best first.
     *
     * @param query free text, already validated as non-blank
     * @param topK  number of hits to return, already clamped to a sane range
     * @throws KnowledgeIndexException when the embedding model or the store is unavailable
     */
    List<RetrievedChunk> search(String query, int topK);
}
