package com.sq.caa.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Uploads are judged on their bytes, never on their name - the whole point is that a spreadsheet
 * renamed to {@code .pdf} must not end up in the corpus the risk agent cites as policy.
 */
class DocumentFormatDetectorTest {

    private final DocumentFormatDetector detector = new DocumentFormatDetector();

    @Test
    @DisplayName("real .docx and .pdf bytes are recognised")
    void acceptsRealDocuments() {
        assertThat(detector.detect("policy.docx", RagDocumentFixtures.styledDocx()))
                .isEqualTo(KnowledgeFormat.DOCX);
        assertThat(detector.detect("policy.pdf", RagDocumentFixtures.typographicPdf()))
                .isEqualTo(KnowledgeFormat.PDF);
    }

    @Test
    @DisplayName("a text file renamed to .pdf is refused")
    void refusesTextRenamedToPdf() {
        byte[] text = "Just some notes, not a PDF.".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> detector.detect("policy.pdf", text))
                .isInstanceOf(UnsupportedDocumentException.class)
                .hasMessageContaining("plain text");
    }

    @Test
    @DisplayName("a spreadsheet renamed to .docx is refused - both are ZIP containers")
    void refusesSpreadsheetRenamedToDocx() {
        assertThatThrownBy(() -> detector.detect("policy.docx", RagDocumentFixtures.xlsxShapedZip()))
                .isInstanceOf(UnsupportedDocumentException.class)
                .hasMessageContaining("Excel workbook");
    }

    @Test
    @DisplayName("a legacy .doc is refused with advice rather than a parser crash")
    void refusesLegacyDoc() {
        assertThatThrownBy(() -> detector.detect("policy.doc", RagDocumentFixtures.legacyDocHeader()))
                .isInstanceOf(UnsupportedDocumentException.class)
                .hasMessageContaining("Save the document as .docx");
    }

    @Test
    @DisplayName("valid content under the wrong extension is refused, so file names cannot lie")
    void refusesExtensionContentMismatch() {
        byte[] pdf = RagDocumentFixtures.typographicPdf();

        assertThatThrownBy(() -> detector.detect("policy.docx", pdf))
                .isInstanceOf(UnsupportedDocumentException.class)
                .hasMessageContaining("named 'policy.docx'");
    }

    @Test
    @DisplayName("an empty upload is refused")
    void refusesEmptyUpload() {
        assertThatThrownBy(() -> detector.detect("policy.pdf", new byte[0]))
                .isInstanceOf(UnsupportedDocumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("a PDF header preceded by junk bytes is still a PDF")
    void toleratesLeadingBytesBeforeThePdfHeader() {
        byte[] pdf = RagDocumentFixtures.typographicPdf();
        byte[] padded = new byte[pdf.length + 8];
        System.arraycopy(pdf, 0, padded, 8, pdf.length);

        assertThat(detector.detect("policy.pdf", padded)).isEqualTo(KnowledgeFormat.PDF);
    }
}
