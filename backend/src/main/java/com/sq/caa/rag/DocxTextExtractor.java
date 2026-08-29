package com.sq.caa.rag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Splits a {@code .docx} into sections along its Word heading styles.
 *
 * <p>Word documents carry their outline explicitly, so this parser does not have to guess: every
 * paragraph whose style resolves to {@code Heading 1}, {@code Heading 2} or {@code Heading 3}
 * starts a new section and its text becomes the section title. Both the style id
 * ({@code Heading1}) and the human-readable style name ({@code heading 1}) are consulted, and a few
 * localised names are recognised too, because a policy written in a non-English Word install uses
 * {@code Überschrift 1} or {@code Titre 1} as the style name while keeping an English style id -
 * or, occasionally, the other way round.
 *
 * <p>Documents produced by exporters that apply direct formatting instead of styles have no heading
 * styles at all. Rather than returning one enormous section, this parser then falls back to
 * {@link HeadingHeuristics}: a short, fully bold or fully capitalised paragraph, or a numbered
 * heading such as {@code 2.1 Thresholds}, opens a section. The fallback is only armed when the
 * document turned out to have no styled headings, so it can never fight with real Word structure.
 *
 * <p>Tables are flattened into pipe-separated rows and appended to the section they sit in - policy
 * thresholds are very often tabulated, and losing them would make the knowledge base useless for
 * exactly the questions the risk agent asks.
 */
@Component
public class DocxTextExtractor implements DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocxTextExtractor.class);

    /** Deepest heading level that still starts a new section. */
    private static final int MAX_SECTION_HEADING_LEVEL = 3;

    /**
     * Style names and ids that identify a heading, after everything but letters and digits has been
     * stripped: {@code Heading 2} and {@code heading2} both normalise to {@code heading2}, and
     * {@code Überschrift 2} to {@code berschrift2} once the umlaut is dropped.
     */
    private static final Pattern HEADING_STYLE = Pattern.compile(
            "^(?:heading|berschrift|uberschrift|titre|titolo|ttulo|titulo|encabezado|kop|nadpis"
                    + "|zagwek|rubrik|otsikko|overskrift|cmcm|h)(\\d)$");

    private static final String TITLE_STYLE = "title";
    private static final String SUBTITLE_STYLE = "subtitle";

    @Override
    public KnowledgeFormat format() {
        return KnowledgeFormat.DOCX;
    }

    @Override
    public ParsedDocument extract(byte[] content, String filename) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<Block> blocks = readBlocks(document);
            if (blocks.stream().noneMatch(block -> !block.text().isBlank())) {
                throw new DocumentExtractionException(filename,
                        "The Word document contains no readable text.");
            }
            boolean styledHeadings = blocks.stream().anyMatch(block -> block.styleLevel() > 0);
            String title = resolveTitle(document, blocks, filename);
            List<ParsedSection> sections = toSections(blocks, styledHeadings, title);
            if (sections.isEmpty()) {
                throw new DocumentExtractionException(filename,
                        "The Word document contains no readable text.");
            }
            log.debug("Parsed '{}' into {} section(s); styled headings: {}", filename,
                    sections.size(), styledHeadings);
            return new ParsedDocument(title, sections);
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DocumentExtractionException(filename,
                    "The Word document could not be read: " + rootMessage(e), e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Reading                                                             */
    /* ------------------------------------------------------------------ */

    private List<Block> readBlocks(XWPFDocument document) {
        XWPFStyles styles = document.getStyles();
        List<Block> blocks = new ArrayList<>();
        for (IBodyElement element : document.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                String text = normalise(paragraph.getText());
                if (text.isBlank()) {
                    continue;
                }
                blocks.add(new Block(text, headingLevel(paragraph, styles), allRunsBold(paragraph),
                        false));
            } else if (element instanceof XWPFTable table) {
                for (String row : flattenTable(table)) {
                    blocks.add(new Block(row, 0, false, true));
                }
            }
        }
        return blocks;
    }

    /** Heading depth declared by the paragraph's style, or 0 when it is body text. */
    private int headingLevel(XWPFParagraph paragraph, XWPFStyles styles) {
        int fromId = levelOfStyleName(paragraph.getStyleID());
        if (fromId > 0) {
            return fromId;
        }
        String styleId = paragraph.getStyleID();
        if (styleId != null && styles != null) {
            XWPFStyle style = styles.getStyle(styleId);
            if (style != null) {
                int fromName = levelOfStyleName(style.getName());
                if (fromName > 0) {
                    return fromName;
                }
            }
        }
        return 0;
    }

    private static int levelOfStyleName(String rawStyle) {
        if (rawStyle == null || rawStyle.isBlank()) {
            return 0;
        }
        String normalised = rawStyle.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (TITLE_STYLE.equals(normalised)) {
            return 1;
        }
        if (SUBTITLE_STYLE.equals(normalised)) {
            return 2;
        }
        Matcher matcher = HEADING_STYLE.matcher(normalised);
        if (!matcher.matches()) {
            return 0;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static boolean allRunsBold(XWPFParagraph paragraph) {
        List<XWPFRun> runs = paragraph.getRuns();
        boolean sawText = false;
        for (XWPFRun run : runs) {
            String text = run.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            sawText = true;
            if (!run.isBold()) {
                return false;
            }
        }
        return sawText;
    }

    private static List<String> flattenTable(XWPFTable table) {
        List<String> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(normalise(cell.getText()));
            }
            String line = String.join(" | ", cells).strip();
            if (!line.isBlank() && !line.chars().allMatch(c -> c == '|' || c == ' ')) {
                rows.add(line);
            }
        }
        return rows;
    }

    /* ------------------------------------------------------------------ */
    /* Structuring                                                         */
    /* ------------------------------------------------------------------ */

    private List<ParsedSection> toSections(List<Block> blocks, boolean styledHeadings, String title) {
        SectionBuilder builder = new SectionBuilder(title);
        boolean skippedTitleBlock = false;
        for (Block block : blocks) {
            int level = sectionLevel(block, styledHeadings);
            if (level > 0) {
                // The document title paragraph is not a section of its own; it named the document.
                if (!skippedTitleBlock && block.text().equals(title)) {
                    skippedTitleBlock = true;
                    continue;
                }
                builder.startSection(block.text(), level);
                continue;
            }
            if (block.tableRow()) {
                builder.appendLine(block.text());
            } else {
                builder.appendParagraph(block.text());
            }
        }
        return builder.build();
    }

    /** Section-opening depth of a block: its style level, or the heuristic fallback. */
    private static int sectionLevel(Block block, boolean styledHeadings) {
        if (block.tableRow()) {
            return 0;
        }
        if (block.styleLevel() > 0) {
            return block.styleLevel() <= MAX_SECTION_HEADING_LEVEL ? block.styleLevel() : 0;
        }
        if (styledHeadings) {
            return 0;
        }
        String text = block.text();
        if (HeadingHeuristics.isNumberedHeading(text)) {
            return Math.min(HeadingHeuristics.numberedHeadingLevel(text), MAX_SECTION_HEADING_LEVEL);
        }
        if (HeadingHeuristics.looksLikeHeading(text)) {
            return 1;
        }
        boolean shortBoldLine = block.bold()
                && text.length() <= HeadingHeuristics.MAX_HEADING_LENGTH
                && HeadingHeuristics.countWords(text) <= HeadingHeuristics.MAX_HEADING_WORDS
                && !HeadingHeuristics.endsLikeSentence(text)
                && !HeadingHeuristics.isBullet(text);
        return shortBoldLine ? 1 : 0;
    }

    private String resolveTitle(XWPFDocument document, List<Block> blocks, String filename) {
        String fromProperties = coreTitle(document);
        if (fromProperties != null && !fromProperties.isBlank()) {
            return HeadingHeuristics.normaliseTitle(fromProperties);
        }
        for (Block block : blocks) {
            if (block.styleLevel() == 1 && !block.text().isBlank()) {
                return HeadingHeuristics.normaliseTitle(block.text());
            }
        }
        return HeadingHeuristics.titleFromFilename(filename);
    }

    private static String coreTitle(XWPFDocument document) {
        try {
            POIXMLProperties properties = document.getProperties();
            return properties == null ? null : properties.getCoreProperties().getTitle();
        } catch (RuntimeException e) {
            log.debug("Could not read core properties, falling back to the first heading", e);
            return null;
        }
    }

    private static String normalise(String text) {
        if (text == null) {
            return "";
        }
        // Word emits non-breaking spaces, soft hyphens and tabs freely; collapse them so token
        // estimation and heading tests see ordinary prose.
        return text.replace(' ', ' ')
                .replace("­", "")
                .replaceAll("[\\t\\x0B\\f\\r]", " ")
                .replaceAll(" {2,}", " ")
                .strip();
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }

    /**
     * One paragraph or table row, with the two properties section detection needs.
     *
     * @param styleLevel heading depth from the paragraph style, 0 for body text
     * @param bold       whether every run carrying text is bold
     * @param tableRow   whether the block came from a table
     */
    private record Block(String text, int styleLevel, boolean bold, boolean tableRow) {
    }
}
