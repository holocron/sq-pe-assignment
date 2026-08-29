package com.sq.caa.rag;

/**
 * One visual line of a PDF, with the typographic measurements section detection needs.
 *
 * <p>PDFs carry no structure, so "is this a heading?" has to be answered from how the line is set:
 * bigger than the body text, or set entirely in bold, or numbered.
 *
 * @param page         one-based page the line was drawn on
 * @param text         the line's text, whitespace-normalised
 * @param maxFontSize  largest glyph size on the line, in points
 * @param boldFraction share of glyphs drawn in a bold face, {@code 0..1}
 * @param glyphs       number of glyphs, used to weight the body-size histogram
 */
public record PdfLine(int page, String text, float maxFontSize, float boldFraction, int glyphs) {

    public boolean isBlank() {
        return text == null || text.isBlank();
    }
}
