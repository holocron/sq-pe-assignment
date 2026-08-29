package com.sq.caa.rag;

import java.util.UUID;

/**
 * One vector-search hit, flattened out of the Spring AI {@code Document} representation.
 *
 * <p>This is what {@link RagService#search(String, int)} returns and therefore what the ReAct
 * agent's {@code search_policy_knowledge} tool and the operator search screen both consume. Every
 * hit carries its provenance, so the agent can cite a policy by file and section rather than
 * paraphrasing an anonymous blob of text.
 *
 * @param chunkId      {@code document_chunks.id}
 * @param documentId   owning knowledge document, null only for a chunk written by an older schema
 * @param filename     source file name
 * @param title        source document title
 * @param sectionTitle heading of the section the text came from
 * @param chunkIndex   position of the chunk within its document
 * @param content      the chunk text
 * @param score        cosine similarity in {@code [0, 1]}; higher is closer
 */
public record RetrievedChunk(String chunkId,
        UUID documentId,
        String filename,
        String title,
        String sectionTitle,
        int chunkIndex,
        String content,
        double score) {

    /** Compact one-line provenance, handy in prompts and logs: {@code policy.pdf > 3. Thresholds}. */
    public String citation() {
        boolean hasSection = sectionTitle != null && !sectionTitle.isBlank();
        String source = filename != null && !filename.isBlank() ? filename : title;
        return hasSection ? source + " > " + sectionTitle : String.valueOf(source);
    }
}
