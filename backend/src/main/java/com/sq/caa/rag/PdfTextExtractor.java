package com.sq.caa.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Splits a {@code .pdf} into sections without any structural help from the file.
 *
 * <p>PDF describes glyph placement, not document outline, so headings have to be inferred. The
 * approach here is measurement first, pattern second:
 *
 * <ol>
 *   <li>Every line is read with its typographic metrics by {@link PdfLineReader}.</li>
 *   <li>The <b>body type size</b> is the size that covers the most glyphs in the document - a
 *       glyph-weighted mode rather than an average, so a single oversized title cannot drag it.</li>
 *   <li>A line is a heading when it is short and either set noticeably larger than the body text,
 *       or set entirely in a bold face at body size, or fully capitalised.</li>
 *   <li>The <b>numbered-heading fallback</b> ({@code 3.}, {@code 4.2 Thresholds},
 *       {@code Appendix B}) catches documents whose author never varied the type at all - common in
 *       policy PDFs exported from plain-text pipelines.</li>
 * </ol>
 *
 * <p>Two clean-up passes run before detection, because both would otherwise poison it: running
 * headers and footers (the same short line repeated across most pages) are dropped, as are bare
 * page numbers. Body lines are then re-joined into paragraphs, since PDF hard-wraps every line and
 * chunking on raw wrapped lines would cut sentences in half.
 *
 * <p><b>Tables</b> are the one shape that must <em>not</em> be re-joined. {@link PdfLineReader}
 * measures the horizontal gaps inside a line and marks column boundaries with
 * {@code |}; a line carrying those marks is kept as its own row rather than being folded into the
 * surrounding prose, which is exactly what the DOCX parser does for a real Word table. Without
 * this, a jurisdiction table flattens into a run-on string in which "SECO KP North Korea" reads as
 * one entry and the agent can attribute the wrong sanctions regime to the wrong country.
 *
 * <p>PDFBox 3 changed loading: documents are opened through {@code Loader.loadPDF(...)} rather than
 * the 2.x {@code PDDocument.load(...)}.
 */
@Component
public class PdfTextExtractor implements DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /** A line must be this much larger than the body text to count as a heading on size alone. */
    private static final float HEADING_SIZE_RATIO = 1.15f;

    /** Tolerance when comparing a bold line's size against the body size. */
    private static final float SIZE_EPSILON = 0.25f;

    /** Share of glyphs that must be bold before a line counts as "set in bold". */
    private static final float BOLD_LINE_THRESHOLD = 0.9f;

    /** Font sizes are bucketed to this granularity when finding the body size. */
    private static final float SIZE_BUCKET = 0.5f;

    /** Repeated-line removal needs at least this many pages to be meaningful. */
    private static final int MIN_PAGES_FOR_RUNNING_HEAD_REMOVAL = 3;

    /** A short line repeated on at least this share of pages is a running header or footer. */
    private static final double RUNNING_HEAD_PAGE_RATIO = 0.5;

    private static final int MAX_RUNNING_HEAD_LENGTH = 100;

    /** {@code 12}, {@code Page 12}, {@code 12 / 40}, {@code - 12 -}. */
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^\\s*[-–—]?\\s*(?:page\\s+)?\\d{1,4}\\s*(?:(?:/|of)\\s*\\d{1,4})?\\s*[-–—]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Deepest numbered heading level that still starts a new section. */
    private static final int MAX_SECTION_HEADING_LEVEL = 3;

    @Override
    public KnowledgeFormat format() {
        return KnowledgeFormat.PDF;
    }

    @Override
    public ParsedDocument extract(byte[] content, String filename) {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new DocumentExtractionException(filename,
                        "The PDF forbids text extraction. Upload a version without copy protection.");
            }
            List<PdfLine> lines = clean(PdfLineReader.read(document), document.getNumberOfPages());
            if (lines.isEmpty()) {
                throw new DocumentExtractionException(filename,
                        "The PDF contains no extractable text. Scanned documents must be run "
                                + "through OCR before they can be indexed.");
            }
            float bodySize = bodyFontSize(lines);
            String title = resolveTitle(document, lines, bodySize, filename);
            List<ParsedSection> sections = toSections(lines, bodySize, title);
            if (sections.isEmpty()) {
                throw new DocumentExtractionException(filename,
                        "The PDF contains no extractable text.");
            }
            log.debug("Parsed '{}' into {} section(s); body type size {}pt", filename,
                    sections.size(), bodySize);
            return new ParsedDocument(title, sections);
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (InvalidPasswordException e) {
            throw new DocumentExtractionException(filename,
                    "The PDF is password protected and cannot be indexed.", e);
        } catch (IOException | RuntimeException e) {
            throw new DocumentExtractionException(filename,
                    "The PDF could not be read: " + rootMessage(e), e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Clean-up                                                            */
    /* ------------------------------------------------------------------ */

    /** Drops page numbers and lines that repeat as running headers or footers. */
    private List<PdfLine> clean(List<PdfLine> lines, int pageCount) {
        Set<String> runningHeads = runningHeads(lines, pageCount);
        List<PdfLine> kept = new ArrayList<>(lines.size());
        for (PdfLine line : lines) {
            if (line.isBlank() || PAGE_NUMBER.matcher(line.text()).matches()) {
                continue;
            }
            if (runningHeads.contains(line.text().toLowerCase(Locale.ROOT))) {
                continue;
            }
            kept.add(line);
        }
        return kept;
    }

    private Set<String> runningHeads(List<PdfLine> lines, int pageCount) {
        if (pageCount < MIN_PAGES_FOR_RUNNING_HEAD_REMOVAL) {
            return Set.of();
        }
        Map<String, Set<Integer>> pagesByLine = new HashMap<>();
        for (PdfLine line : lines) {
            if (line.isBlank() || line.text().length() > MAX_RUNNING_HEAD_LENGTH) {
                continue;
            }
            pagesByLine.computeIfAbsent(line.text().toLowerCase(Locale.ROOT), key -> new HashSet<>())
                    .add(line.page());
        }
        int threshold = Math.max(MIN_PAGES_FOR_RUNNING_HEAD_REMOVAL,
                (int) Math.ceil(pageCount * RUNNING_HEAD_PAGE_RATIO));
        Set<String> repeated = new HashSet<>();
        pagesByLine.forEach((text, pages) -> {
            if (pages.size() >= threshold) {
                repeated.add(text);
            }
        });
        return repeated;
    }

    /* ------------------------------------------------------------------ */
    /* Measurement                                                         */
    /* ------------------------------------------------------------------ */

    /** The type size that carries the most glyphs, which is by definition the body text. */
    private float bodyFontSize(List<PdfLine> lines) {
        Map<Float, Integer> glyphsBySize = new LinkedHashMap<>();
        for (PdfLine line : lines) {
            float bucket = bucket(line.maxFontSize());
            glyphsBySize.merge(bucket, line.glyphs(), Integer::sum);
        }
        float bodySize = 0f;
        int best = -1;
        for (Map.Entry<Float, Integer> entry : glyphsBySize.entrySet()) {
            if (entry.getValue() > best || (entry.getValue() == best && entry.getKey() < bodySize)) {
                best = entry.getValue();
                bodySize = entry.getKey();
            }
        }
        return bodySize;
    }

    private static float bucket(float size) {
        return Math.round(size / SIZE_BUCKET) * SIZE_BUCKET;
    }

    /* ------------------------------------------------------------------ */
    /* Structuring                                                         */
    /* ------------------------------------------------------------------ */

    private List<ParsedSection> toSections(List<PdfLine> lines, float bodySize, String title) {
        SectionBuilder builder = new SectionBuilder(title);
        List<PdfLine> pending = new ArrayList<>();
        boolean skippedTitleLine = false;

        for (PdfLine line : lines) {
            int level = headingLevel(line, bodySize);
            if (level > 0) {
                if (!skippedTitleLine && line.text().equals(title)) {
                    skippedTitleLine = true;
                    continue;
                }
                flushParagraphs(builder, pending);
                builder.startSection(line.text(), level);
            } else {
                pending.add(line);
            }
        }
        flushParagraphs(builder, pending);
        return builder.build();
    }

    /**
     * Re-joins hard-wrapped lines into paragraphs.
     *
     * <p>A paragraph ends when a line finishes a sentence and is visibly shorter than the block's
     * full measure - the classic "last line of a paragraph" shape - or when the next line opens a
     * bullet. Everything else is a continuation and is joined with a space.
     */
    private void flushParagraphs(SectionBuilder builder, List<PdfLine> pending) {
        if (pending.isEmpty()) {
            return;
        }
        int fullMeasure = pending.stream()
                .filter(line -> !isTableRow(line.text()))
                .mapToInt(line -> line.text().length())
                .max()
                .orElse(0);
        StringBuilder paragraph = new StringBuilder();
        for (int i = 0; i < pending.size(); i++) {
            String text = pending.get(i).text();

            // A measured table row keeps its own line, so the row and cell boundaries survive into
            // the embedded chunk instead of dissolving into the prose around it.
            if (isTableRow(text)) {
                if (paragraph.length() > 0) {
                    builder.appendParagraph(paragraph.toString());
                    paragraph.setLength(0);
                }
                builder.appendLine(text);
                continue;
            }

            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(text);

            boolean last = i == pending.size() - 1;
            boolean nextIsBullet = !last && HeadingHeuristics.isBullet(pending.get(i + 1).text());
            boolean nextIsTableRow = !last && isTableRow(pending.get(i + 1).text());
            boolean thisIsBullet = HeadingHeuristics.isBullet(text);
            boolean shortLastLine = text.length() < fullMeasure * 0.85;
            boolean sentenceEnd = endsSentence(text);

            if (last || nextIsBullet || nextIsTableRow || (sentenceEnd && shortLastLine)
                    || (thisIsBullet && sentenceEnd)) {
                builder.appendParagraph(paragraph.toString());
                paragraph.setLength(0);
            }
        }
        pending.clear();
    }

    /** True when {@link PdfLineReader} measured column boundaries inside the line. */
    static boolean isTableRow(String text) {
        return text != null && text.contains(PdfLineReader.COLUMN_SEPARATOR.strip());
    }

    private static boolean endsSentence(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        char last = text.strip().charAt(text.strip().length() - 1);
        return last == '.' || last == '!' || last == '?' || last == ':' || last == ';';
    }

    /** Section-opening depth of a line: 0 when it is body text. */
    private int headingLevel(PdfLine line, float bodySize) {
        String text = line.text();
        if (text.isBlank() || HeadingHeuristics.isBullet(text) || isTableRow(text)) {
            return 0;
        }
        if (HeadingHeuristics.isNumberedHeading(text)) {
            return Math.min(HeadingHeuristics.numberedHeadingLevel(text), MAX_SECTION_HEADING_LEVEL);
        }
        if (text.length() > HeadingHeuristics.MAX_HEADING_LENGTH
                || HeadingHeuristics.countWords(text) > HeadingHeuristics.MAX_HEADING_WORDS) {
            return 0;
        }
        if (HeadingHeuristics.endsLikeSentence(text)) {
            return 0;
        }
        if (line.maxFontSize() >= bodySize * HEADING_SIZE_RATIO && bodySize > 0f) {
            // Bigger type: level 1 for the largest jumps, level 2 for a modest one.
            return line.maxFontSize() >= bodySize * 1.4f ? 1 : 2;
        }
        if (line.boldFraction() >= BOLD_LINE_THRESHOLD
                && line.maxFontSize() >= bodySize - SIZE_EPSILON) {
            return 2;
        }
        return HeadingHeuristics.looksLikeHeading(text) ? 1 : 0;
    }

    private String resolveTitle(PDDocument document, List<PdfLine> lines, float bodySize,
            String filename) {
        String metadataTitle = documentInfoTitle(document);
        if (metadataTitle != null && !metadataTitle.isBlank()) {
            return HeadingHeuristics.normaliseTitle(metadataTitle);
        }
        // Otherwise: the largest line on the first page, which is how title pages are set.
        PdfLine best = null;
        for (PdfLine line : lines) {
            if (line.page() > 1) {
                break;
            }
            if (line.isBlank() || line.text().length() > HeadingHeuristics.MAX_HEADING_LENGTH) {
                continue;
            }
            if (best == null || line.maxFontSize() > best.maxFontSize()) {
                best = line;
            }
        }
        if (best != null && best.maxFontSize() > bodySize) {
            return HeadingHeuristics.normaliseTitle(best.text());
        }
        return HeadingHeuristics.titleFromFilename(filename);
    }

    private static String documentInfoTitle(PDDocument document) {
        try {
            return document.getDocumentInformation() == null
                    ? null
                    : document.getDocumentInformation().getTitle();
        } catch (RuntimeException e) {
            log.debug("Could not read PDF document information", e);
            return null;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
