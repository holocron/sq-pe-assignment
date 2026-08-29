package com.sq.caa.rag;

import java.util.List;

/**
 * The structural result of reading an uploaded file: a document title and the sections it was
 * split into. Chunking and embedding operate on this, never on the raw file.
 *
 * @param title    document title, taken from the file's own metadata, its first heading or, as a
 *                 last resort, the file name
 * @param sections sections in document order; never empty for a document that had any text
 */
public record ParsedDocument(String title, List<ParsedSection> sections) {

    public ParsedDocument {
        sections = List.copyOf(sections);
    }

    public int sectionCount() {
        return sections.size();
    }
}
