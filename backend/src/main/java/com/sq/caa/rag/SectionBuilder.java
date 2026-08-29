package com.sq.caa.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles {@link ParsedSection}s as a parser walks a document top to bottom.
 *
 * <p>Both parsers produce the same event shape - "here is a heading" or "here is a line of body
 * text" - so the bookkeeping (closing the previous section, dropping empty ones, naming the text
 * that appears before the first heading) lives here once instead of twice.
 */
final class SectionBuilder {

    private final List<ParsedSection> sections = new ArrayList<>();
    private final StringBuilder body = new StringBuilder();
    private final String documentTitle;

    private String currentTitle;
    private int currentLevel;

    /**
     * @param documentTitle title given to the text that precedes the first heading, so a chunk cut
     *                      from a preamble is still attributable to something meaningful
     */
    SectionBuilder(String documentTitle) {
        this.documentTitle = documentTitle;
        this.currentTitle = documentTitle;
        this.currentLevel = 0;
    }

    /** Closes the section in progress and opens a new one. */
    void startSection(String title, int level) {
        closeCurrent();
        String normalised = HeadingHeuristics.normaliseTitle(title);
        this.currentTitle = normalised.isEmpty() ? documentTitle : normalised;
        this.currentLevel = Math.max(level, 1);
    }

    /** Appends a paragraph to the section in progress. Blank input is ignored. */
    void appendParagraph(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (body.length() > 0) {
            body.append("\n\n");
        }
        body.append(text.strip());
    }

    /** Appends a line that belongs to the same paragraph as the previous one. */
    void appendLine(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (body.length() > 0) {
            body.append('\n');
        }
        body.append(text.strip());
    }

    /** True when nothing at all has been recorded yet. */
    boolean isEmpty() {
        return sections.isEmpty() && body.length() == 0;
    }

    /** Closes the last section and returns everything collected, dropping sections with no text. */
    List<ParsedSection> build() {
        closeCurrent();
        List<ParsedSection> ordered = new ArrayList<>(sections.size());
        for (ParsedSection section : sections) {
            ordered.add(new ParsedSection(ordered.size(), section.level(), section.title(),
                    section.text()));
        }
        return ordered;
    }

    private void closeCurrent() {
        String text = body.toString().strip();
        body.setLength(0);
        if (text.isEmpty()) {
            // A heading with no text of its own (a chapter title above sub-headings, say) carries no
            // information worth embedding on its own, so it is dropped rather than emitted as a
            // whitespace-only chunk.
            return;
        }
        sections.add(new ParsedSection(sections.size(), currentLevel, currentTitle, text));
    }
}
