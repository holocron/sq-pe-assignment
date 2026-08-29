package com.sq.caa.rag;

/**
 * One embeddable window of text.
 *
 * <p>A section that fits the token budget produces a single chunk; a longer one produces several
 * overlapping windows that all keep the same {@code sectionTitle}, so a citation always names the
 * policy section it came from.
 *
 * @param chunkIndex   zero-based position within the whole document
 * @param sectionIndex zero-based position of the source section
 * @param sectionTitle heading of the source section
 * @param windowIndex  zero-based position of this window inside its section
 * @param windowCount  number of windows the section was split into
 * @param content      the text that gets embedded, prefixed with the section heading
 * @param tokenEstimate estimated token length of {@link #content}
 */
public record TextChunk(int chunkIndex,
        int sectionIndex,
        String sectionTitle,
        int windowIndex,
        int windowCount,
        String content,
        int tokenEstimate) {
}
