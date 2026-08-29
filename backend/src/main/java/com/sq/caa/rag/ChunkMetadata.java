package com.sq.caa.rag;

import com.sq.caa.domain.KnowledgeDocument;

/**
 * Metadata keys written onto every {@code document_chunks} row.
 *
 * <p>These are the contract between ingestion, deletion (which filters on {@link #DOCUMENT_ID}) and
 * search (which renders the source of every hit). BUILD_SPEC section 2 fixes the first five.
 */
public final class ChunkMetadata {

    /** Owning {@code knowledge_documents.document_id}, as a UUID string. */
    public static final String DOCUMENT_ID = KnowledgeDocument.METADATA_DOCUMENT_ID;

    /** Original upload file name. */
    public static final String FILENAME = "filename";

    /** Document title. */
    public static final String TITLE = "title";

    /** Heading of the section the chunk was cut from. */
    public static final String SECTION_TITLE = "section_title";

    /** Zero-based position of the chunk within its document. */
    public static final String CHUNK_INDEX = "chunk_index";

    /** Zero-based position of the source section within its document. */
    public static final String SECTION_INDEX = "section_index";

    private ChunkMetadata() {
    }
}
