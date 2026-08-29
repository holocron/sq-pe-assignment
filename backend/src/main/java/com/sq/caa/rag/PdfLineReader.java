package com.sq.caa.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * A {@link PDFTextStripper} that yields measured lines instead of a flat string.
 *
 * <p>{@code PDFTextStripper} normally streams words to a {@code Writer} and throws the glyph
 * metrics away. Overriding the word and line hooks keeps the {@link TextPosition}s alongside the
 * text, which is what lets {@link PdfTextExtractor} compare a line's type size against the body
 * text and detect headings in documents that carry no structural tags at all.
 */
final class PdfLineReader extends PDFTextStripper {

    /** A face is treated as bold from this weight up; 400 is regular, 700 is bold. */
    private static final float BOLD_FONT_WEIGHT = 600f;

    private final List<PdfLine> lines = new ArrayList<>();
    private final StringBuilder lineText = new StringBuilder();

    private float maxFontSize;
    private int glyphCount;
    private int boldGlyphCount;

    PdfLineReader() throws IOException {
        super();
        setSortByPosition(true);
        setSuppressDuplicateOverlappingText(true);
    }

    /** Reads every line of the document, in reading order. */
    static List<PdfLine> read(PDDocument document) throws IOException {
        PdfLineReader reader = new PdfLineReader();
        reader.getText(document);
        return reader.lines;
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) {
        lineText.append(text);
        for (TextPosition position : textPositions) {
            String unicode = position.getUnicode();
            if (unicode == null || unicode.isBlank()) {
                continue;
            }
            glyphCount += unicode.length();
            maxFontSize = Math.max(maxFontSize, position.getFontSizeInPt());
            if (isBold(position.getFont())) {
                boldGlyphCount += unicode.length();
            }
        }
    }

    @Override
    protected void writeWordSeparator() {
        lineText.append(' ');
    }

    @Override
    protected void writeLineSeparator() {
        flushLine();
    }

    @Override
    protected void writeParagraphSeparator() {
        flushLine();
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
        flushLine();
        super.endPage(page);
    }

    private void flushLine() {
        String text = lineText.toString().replaceAll("\\s+", " ").strip();
        if (!text.isEmpty() && glyphCount > 0) {
            float boldFraction = (float) boldGlyphCount / glyphCount;
            lines.add(new PdfLine(getCurrentPageNo(), text, maxFontSize, boldFraction, glyphCount));
        }
        lineText.setLength(0);
        maxFontSize = 0f;
        glyphCount = 0;
        boldGlyphCount = 0;
    }

    /**
     * Bold detection has to work for both well-described embedded fonts and the many PDFs whose
     * descriptors are missing or wrong, so the descriptor flags, the declared weight and the font
     * name are all consulted.
     */
    private static boolean isBold(PDFont font) {
        if (font == null) {
            return false;
        }
        PDFontDescriptor descriptor = font.getFontDescriptor();
        if (descriptor != null) {
            if (descriptor.isForceBold() || descriptor.getFontWeight() >= BOLD_FONT_WEIGHT) {
                return true;
            }
        }
        String name = font.getName();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("bold") || lower.contains("black") || lower.contains("heavy")
                || lower.contains("semib") || lower.contains("demib");
    }
}
