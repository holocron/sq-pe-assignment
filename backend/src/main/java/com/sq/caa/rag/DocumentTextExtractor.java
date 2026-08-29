package com.sq.caa.rag;

/**
 * Reads an uploaded file into the structural {@link ParsedDocument} that chunking operates on.
 *
 * <p>One implementation per accepted {@link KnowledgeFormat}; {@code RagService} selects by format,
 * so adding a third format later is a matter of adding a bean.
 */
public interface DocumentTextExtractor {

    /** The format this extractor handles. */
    KnowledgeFormat format();

    /**
     * Parses the file into titled sections.
     *
     * @param content  complete file bytes, already verified to be of {@link #format()}
     * @param filename original file name, used only to derive a fallback title
     * @throws DocumentExtractionException when the file cannot be read or holds no text
     */
    ParsedDocument extract(byte[] content, String filename);
}
