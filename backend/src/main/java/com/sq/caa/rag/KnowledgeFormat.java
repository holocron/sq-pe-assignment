package com.sq.caa.rag;

import java.util.List;
import java.util.Locale;

/**
 * The two document formats the knowledge base accepts.
 *
 * <p>The assignment restricts uploads to Word and PDF policy documents. Everything else is refused
 * by {@link DocumentFormatDetector} before a single byte is parsed.
 */
public enum KnowledgeFormat {

    /** Office Open XML word processing document ({@code .docx}). */
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),

    /** Portable Document Format ({@code .pdf}). */
    PDF("application/pdf", "pdf");

    private final String mimeType;
    private final String extension;

    KnowledgeFormat(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    /** Canonical media type, stored on {@code knowledge_documents.mime_type}. */
    public String mimeType() {
        return mimeType;
    }

    /** Canonical file extension, without the leading dot. */
    public String extension() {
        return extension;
    }

    /** Human-readable list of what an upload may be, used in rejection messages. */
    public static String acceptedDescription() {
        return List.of(DOCX, PDF).stream()
                .map(format -> "." + format.extension())
                .reduce((left, right) -> left + " or " + right)
                .orElseThrow();
    }

    /** The format implied by a file name, or {@code null} when the name says nothing useful. */
    public static KnowledgeFormat fromFilename(String filename) {
        if (filename == null) {
            return null;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        for (KnowledgeFormat format : values()) {
            if (lower.endsWith("." + format.extension())) {
                return format;
            }
        }
        return null;
    }
}
