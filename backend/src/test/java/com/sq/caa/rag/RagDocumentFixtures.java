package com.sq.caa.rag;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * Real {@code .docx} and {@code .pdf} bytes, built with the same libraries that parse them.
 *
 * <p>Fixtures on disk would be opaque - a reader could not tell which heading style or which type
 * size a test depends on - so every document here is generated from code that states exactly that.
 */
final class RagDocumentFixtures {

    private RagDocumentFixtures() {
    }

    /** A policy with Word heading styles, a table of thresholds, and text before the first heading. */
    static byte[] styledDocx() {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            heading(document, "AML Transaction Monitoring Policy", 1);
            body(document, "This policy applies to every retail and corporate client of the bank.");
            heading(document, "Reporting thresholds", 2);
            body(document, "A single payment of 10,000 USD or more must be reported to the financial "
                    + "intelligence unit within one business day.");
            table(document, List.of(List.of("Activity", "Threshold"),
                    List.of("Payment", "10,000 USD"),
                    List.of("Crypto", "25,000 USD")));
            heading(document, "Sanctioned jurisdictions", 2);
            body(document, "Transfers to banks domiciled in Iran, North Korea or Syria are prohibited "
                    + "and must be escalated to compliance immediately.");
            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the .docx fixture", e);
        }
    }

    /** A Word document with no heading styles at all: only bold, short, numbered paragraphs. */
    static byte[] unstyledDocx() {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            boldParagraph(document, "1. Velocity limits");
            body(document, "More than twelve payments in twenty-four hours is a velocity alert.");
            boldParagraph(document, "2. Card-not-present risk");
            body(document, "A card-not-present success after a burst of declines is escalated.");
            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the unstyled .docx fixture", e);
        }
    }

    /**
     * A PDF whose headings are distinguishable only typographically: 18pt bold title, 14pt bold
     * headings, 11pt regular body. No structure tags, exactly like a real exported policy.
     */
    static byte[] typographicPdf() {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setLeading(16f);
                content.newLineAtOffset(60, 740);
                line(content, bold(), 18, "Crypto Asset Risk Policy");
                line(content, bold(), 14, "Privacy coins and mixers");
                for (String text : List.of(
                        "Transfers involving privacy-preserving blockchains such as Monero, or any",
                        "address associated with a mixing service, are treated as high risk and",
                        "require enhanced due diligence before the transfer is released.")) {
                    line(content, regular(), 11, text);
                }
                line(content, bold(), 14, "Unnamed exchanges");
                for (String text : List.of(
                        "A crypto transaction without a named exchange counterparty cannot be",
                        "attributed and must be escalated when the aggregate exposure of the",
                        "customer exceeds 25,000 USD over any thirty day period.")) {
                    line(content, regular(), 11, text);
                }
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the .pdf fixture", e);
        }
    }

    /** A PDF set in one single type size, so only the numbered-heading fallback can find sections. */
    static byte[] uniformTypePdf() {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setLeading(14f);
                content.newLineAtOffset(60, 740);
                for (String text : List.of(
                        "1. Wire transfer controls",
                        "Every wire above 75,000 currency units is reviewed by a second officer",
                        "before release, and the beneficiary bank country is screened.",
                        "2. Correspondent banking",
                        "Correspondent relationships with banks in offshore secrecy jurisdictions",
                        "are subject to annual re-approval by the compliance committee.")) {
                    line(content, regular(), 11, text);
                }
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the uniform .pdf fixture", e);
        }
    }

    /**
     * A PDF with a real table: cells drawn at fixed x offsets under a bold heading, the way a
     * policy exported from a word processor or a reporting tool renders a threshold schedule.
     *
     * <p>PDF has no notion of a table, so the only thing distinguishing this from prose is the
     * horizontal gap between the cells - which is exactly what the extractor has to measure.
     */
    static byte[] tabulatedPdf() {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                textAt(content, bold(), 16, 60, 740, "Sanctions and High-Risk Jurisdictions Policy");
                textAt(content, bold(), 13, 60, 700, "2.1 Comprehensive measures");
                textAt(content, regular(), 11, 60, 675,
                        "The jurisdictions below are subject to the regimes named against them.");
                float y = 650;
                for (String[] row : new String[][] {
                        {"ISO-2", "Jurisdiction", "Regime"},
                        {"IR", "Iran", "Comprehensive - UN, EU, OFAC, SECO"},
                        {"KP", "North Korea", "Comprehensive - UN, EU, OFAC, SECO"},
                        {"SY", "Syria", "Comprehensive - EU, OFAC, SECO"},
                        {"RU", "Russian Federation", "Sectoral and financial - EU, OFAC, OFSI"}}) {
                    textAt(content, regular(), 11, 60, y, row[0]);
                    textAt(content, regular(), 11, 130, y, row[1]);
                    textAt(content, regular(), 11, 260, y, row[2]);
                    y -= 18;
                }
                textAt(content, regular(), 11, 60, y - 12,
                        "Any exposure to a jurisdiction listed above is escalated immediately.");
            }
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the tabulated .pdf fixture", e);
        }
    }

    /**
     * A Word document whose body text inherited a {@code Heading 2} style - a common authoring
     * accident, and one nothing in the format prevents.
     */
    static byte[] docxWithARunawayHeading(int headingCharacters) {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            heading(document, "Runaway Heading Policy", 1);
            StringBuilder runaway = new StringBuilder();
            while (runaway.length() < headingCharacters) {
                runaway.append("a reportable instruction is one that the officer records ");
            }
            heading(document, runaway.toString().strip(), 2);
            for (int i = 0; i < 6; i++) {
                body(document, "Paragraph " + i + " of the body sets out the review the officer "
                        + "performs before the instruction is released to the beneficiary bank, "
                        + "and the evidence that must be retained for the statutory period.");
            }
            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the runaway-heading .docx fixture", e);
        }
    }

    /** A PDF that contains no text at all - the shape of a scanned document. */
    static byte[] textlessPdf() {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the empty .pdf fixture", e);
        }
    }

    /** A ZIP container holding an Excel main part: the classic "renamed .xlsx" upload. */
    static byte[] xlsxShapedZip() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.write("<workbook/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the .xlsx fixture", e);
        }
    }

    /** The OLE2 signature of a legacy {@code .doc} file. */
    static byte[] legacyDocHeader() {
        byte[] content = new byte[64];
        byte[] signature = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1,
                0x1A, (byte) 0xE1};
        System.arraycopy(signature, 0, content, 0, signature.length);
        return content;
    }

    /* ------------------------------------------------------------------ */

    private static void heading(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading" + level);
        paragraph.createRun().setText(text);
    }

    private static void boldParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setText(text);
    }

    private static void body(XWPFDocument document, String text) {
        document.createParagraph().createRun().setText(text);
    }

    private static void table(XWPFDocument document, List<List<String>> rows) {
        XWPFTable table = document.createTable(rows.size(), rows.get(0).size());
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = table.getRow(r);
            for (int c = 0; c < rows.get(r).size(); c++) {
                row.getCell(c).setText(rows.get(r).get(c));
            }
        }
    }

    private static PDType1Font bold() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    }

    private static PDType1Font regular() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private static void line(PDPageContentStream content, PDType1Font font, float size, String text)
            throws Exception {
        content.setFont(font, size);
        content.newLine();
        content.showText(text);
    }

    /** Draws one string at an absolute position, which is how table cells are laid out. */
    private static void textAt(PDPageContentStream content, PDType1Font font, float size, float x,
            float y, String text) throws Exception {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }
}
