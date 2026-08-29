package com.sq.caa.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Section detection in both formats: Word's declared outline, and the PDF heuristics. */
class DocumentTextExtractorTest {

    @Nested
    @DisplayName("DOCX")
    class Docx {

        private final DocxTextExtractor extractor = new DocxTextExtractor();

        @Test
        @DisplayName("Word heading styles are the section boundaries and become the section titles")
        void splitsOnHeadingStyles() {
            ParsedDocument document =
                    extractor.extract(RagDocumentFixtures.styledDocx(), "aml-policy.docx");

            assertThat(document.title()).isEqualTo("AML Transaction Monitoring Policy");
            assertThat(document.sections()).extracting(ParsedSection::title)
                    .containsExactly("AML Transaction Monitoring Policy", "Reporting thresholds",
                            "Sanctioned jurisdictions");
            assertThat(document.sections().get(1).text()).contains("10,000 USD or more");
            assertThat(document.sections().get(2).text()).contains("North Korea");
        }

        @Test
        @DisplayName("tables are flattened into their section - thresholds are usually tabulated")
        void keepsTableRows() {
            ParsedDocument document =
                    extractor.extract(RagDocumentFixtures.styledDocx(), "aml-policy.docx");

            assertThat(document.sections().get(1).text())
                    .contains("Activity | Threshold")
                    .contains("Crypto | 25,000 USD");
        }

        @Test
        @DisplayName("a document with no heading styles falls back to the shape of its paragraphs")
        void fallsBackToHeuristicsWithoutStyles() {
            ParsedDocument document =
                    extractor.extract(RagDocumentFixtures.unstyledDocx(), "velocity.docx");

            assertThat(document.sections()).extracting(ParsedSection::title)
                    .containsExactly("1. Velocity limits", "2. Card-not-present risk");
            assertThat(document.sections().get(0).text()).contains("twelve payments");
        }

        @Test
        @DisplayName("a corrupt file fails as a bad request, not as a parser stack trace")
        void rejectsCorruptDocx() {
            byte[] corrupt = "PK and then nonsense".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> extractor.extract(corrupt, "broken.docx"))
                    .isInstanceOf(DocumentExtractionException.class)
                    .hasMessageContaining("could not be read");
        }
    }

    @Nested
    @DisplayName("PDF")
    class Pdf {

        private final PdfTextExtractor extractor = new PdfTextExtractor();

        @Test
        @DisplayName("headings are found from type size and boldness against the body text")
        void splitsOnTypography() {
            ParsedDocument document =
                    extractor.extract(RagDocumentFixtures.typographicPdf(), "crypto-policy.pdf");

            assertThat(document.title()).isEqualTo("Crypto Asset Risk Policy");
            assertThat(document.sections()).extracting(ParsedSection::title)
                    .containsExactly("Privacy coins and mixers", "Unnamed exchanges");
            assertThat(document.sections().get(0).text()).contains("Monero");
            assertThat(document.sections().get(1).text()).contains("25,000 USD");
        }

        @Test
        @DisplayName("hard-wrapped lines are re-joined into sentences, not chunked as lines")
        void rejoinsWrappedLines() {
            ParsedDocument document =
                    extractor.extract(RagDocumentFixtures.typographicPdf(), "crypto-policy.pdf");

            assertThat(document.sections().get(0).text())
                    .contains("mixing service, are treated as high risk and require enhanced");
        }

        @Test
        @DisplayName("a PDF set in one type size still splits on numbered headings")
        void fallsBackToNumberedHeadings() {
            ParsedDocument document =
                    extractor.extract(RagDocumentFixtures.uniformTypePdf(), "wires.pdf");

            assertThat(document.sections()).extracting(ParsedSection::title)
                    .containsExactly("1. Wire transfer controls", "2. Correspondent banking");
        }

        @Test
        @DisplayName("a PDF with no extractable text is refused with OCR advice")
        void rejectsScannedPdf() {
            assertThatThrownBy(() ->
                    extractor.extract(RagDocumentFixtures.textlessPdf(), "scan.pdf"))
                    .isInstanceOf(DocumentExtractionException.class)
                    .hasMessageContaining("OCR");
        }
    }

    @Test
    @DisplayName("every emitted section carries text, so no chunk can ever be blank")
    void sectionsAreNeverEmpty() {
        ParsedDocument[] documents = {
                new DocxTextExtractor().extract(RagDocumentFixtures.styledDocx(), "a.docx"),
                new PdfTextExtractor().extract(RagDocumentFixtures.typographicPdf(), "b.pdf")};

        for (ParsedDocument document : documents) {
            assertThat(document.sections()).isNotEmpty().allSatisfy(section -> {
                assertThat(section.hasText()).isTrue();
                assertThat(section.title()).isNotBlank();
            });
        }
    }
}
