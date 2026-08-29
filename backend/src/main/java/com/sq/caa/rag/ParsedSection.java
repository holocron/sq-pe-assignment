package com.sq.caa.rag;

/**
 * One section of a parsed document: the heading text plus everything under it up to the next
 * heading of the same or a higher level.
 *
 * @param order zero-based position of the section in the document
 * @param level heading depth, 1 for a top-level heading; 0 for text that precedes any heading
 * @param title heading text, never blank (text before the first heading inherits the document title)
 * @param text  the section body with the heading removed, already whitespace-normalised
 */
public record ParsedSection(int order, int level, String title, String text) {

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
