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
 *
 * <p>The same positions also recover <b>column boundaries</b>. A PDF has no notion of a table: a
 * table row is drawn as glyph runs separated by wide horizontal gaps, and the stripper's own
 * word separator collapses those gaps to a single space, which is indistinguishable from the space
 * between two words of a sentence. Every inter-word gap is therefore measured, and a gap wider than
 * {@link #COLUMN_GAP_EMS} ems is emitted as {@link #COLUMN_SEPARATOR} instead. That is the same
 * pipe-separated shape the DOCX parser produces for a real Word table, so a tabulated threshold
 * survives ingestion in both formats.
 */
final class PdfLineReader extends PDFTextStripper {

    /** A face is treated as bold from this weight up; 400 is regular, 700 is bold. */
    private static final float BOLD_FONT_WEIGHT = 600f;

    /**
     * Written between two glyph runs separated by more than {@link #COLUMN_GAP_EMS} ems. Matches
     * what {@code DocxTextExtractor.flattenTable} writes between the cells of a Word table row.
     */
    static final String COLUMN_SEPARATOR = " | ";

    /**
     * Horizontal gap, in ems of the preceding text, above which a break is read as a column
     * boundary rather than a word space. A word space is roughly 0.25 em and even a heavily
     * justified line rarely stretches one past 0.8 em, while table cells are separated by several
     * points of padding plus the slack of the column itself. 1.6 em therefore sits well clear of
     * both, which was checked against the shipped sample PDF: every row of its jurisdiction tables
     * splits into cells and no line of running prose gains a separator.
     */
    private static final float COLUMN_GAP_EMS = 1.6f;

    private final List<PdfLine> lines = new ArrayList<>();
    private final StringBuilder lineText = new StringBuilder();

    private float maxFontSize;
    private int glyphCount;
    private int boldGlyphCount;

    /** Right edge of the last glyph written on the current line, in text space. */
    private float lastGlyphEndX = Float.NaN;

    /** Type size of that glyph, used to express the following gap in ems. */
    private float lastGlyphFontSize;

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
        promoteWordSeparatorToColumnSeparator(textPositions);
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
            lastGlyphEndX = position.getXDirAdj() + position.getWidthDirAdj();
            lastGlyphFontSize = Math.max(position.getFontSizeInPt(), 1f);
        }
    }

    /**
     * Turns the single space {@link #writeWordSeparator()} just wrote into a column separator when
     * the gap it stands for is too wide to be a word space.
     */
    private void promoteWordSeparatorToColumnSeparator(List<TextPosition> textPositions) {
        if (Float.isNaN(lastGlyphEndX) || textPositions.isEmpty()) {
            return;
        }
        if (lineText.length() == 0 || lineText.charAt(lineText.length() - 1) != ' ') {
            return;
        }
        float startX = textPositions.get(0).getXDirAdj();
        float gap = startX - lastGlyphEndX;
        if (gap <= lastGlyphFontSize * COLUMN_GAP_EMS) {
            return;
        }
        lineText.setLength(lineText.length() - 1);
        lineText.append(COLUMN_SEPARATOR);
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
        // Collapse runs of whitespace, but keep the column separators intact: they were measured,
        // not guessed, and the naive collapse would turn them back into ordinary spaces.
        String text = lineText.toString().replaceAll("[ \\t\\x0B\\f\\r\\n]+", " ").strip();
        text = text.replaceAll("\\s*\\|\\s*", COLUMN_SEPARATOR).strip();
        while (text.startsWith("|")) {
            text = text.substring(1).strip();
        }
        while (text.endsWith("|")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        if (!text.isEmpty() && glyphCount > 0) {
            float boldFraction = (float) boldGlyphCount / glyphCount;
            lines.add(new PdfLine(getCurrentPageNo(), text, maxFontSize, boldFraction, glyphCount));
        }
        lineText.setLength(0);
        maxFontSize = 0f;
        glyphCount = 0;
        boldGlyphCount = 0;
        lastGlyphEndX = Float.NaN;
        lastGlyphFontSize = 0f;
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
