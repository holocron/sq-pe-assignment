package com.sq.caa.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        @DisplayName("a paragraph that inherited a heading style is body text, not a heading")
        void doesNotTreatARunawayStyledParagraphAsAHeading() {
            ParsedDocument document = extractor.extract(
                    RagDocumentFixtures.docxWithARunawayHeading(10_000), "runaway.docx");

            assertThat(document.sections()).isNotEmpty().allSatisfy(section ->
                    assertThat(section.title().length())
                            .isLessThanOrEqualTo(HeadingHeuristics.MAX_HEADING_LENGTH));
            // The text is not lost - it is kept where it belongs, in the body.
            assertThat(document.sections()).anySatisfy(section ->
                    assertThat(section.text()).contains("a reportable instruction is one that"));
            assertThat(document.title()).isEqualTo("Runaway Heading Policy");
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
        @DisplayName("table rows keep their row and cell boundaries instead of running together")
        void keepsPdfTableStructure() {
            ParsedDocument document =
                    extractor.extract(RagDocumentFixtures.tabulatedPdf(), "sanctions.pdf");

            String section = document.sections().stream()
                    .filter(candidate -> candidate.title().startsWith("2.1"))
                    .findFirst()
                    .orElseThrow()
                    .text();

            // Cells are delimited, so a country cannot be read as part of the previous regime.
            assertThat(section)
                    .contains("ISO-2 | Jurisdiction | Regime")
                    .contains("RU | Russian Federation | Sectoral and financial - EU, OFAC, OFSI")
                    .contains("KP | North Korea | Comprehensive - UN, EU, OFAC, SECO");
            // Each row is its own line rather than being folded into a run-on paragraph.
            assertThat(section.lines().filter(line -> line.contains(" | ")).count()).isEqualTo(5);
            assertThat(section).doesNotContain("SECO KP North Korea");
            // Ordinary prose in the same section is untouched.
            assertThat(section).contains("subject to the regimes named against them.");
            assertThat(section.lines()
                    .filter(line -> line.startsWith("The jurisdictions below"))
                    .findFirst()
                    .orElseThrow())
                    .doesNotContain("|");
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

    /**
     * The documents that actually ship inside the jar and seed every fresh deployment. Synthetic
     * fixtures prove the parsing rules; this proves they hold for the corpus the agent will really
     * cite.
     */
    @Nested
    @DisplayName("the shipped sample corpus")
    class ShippedCorpus {

        @Test
        @DisplayName("the sanctions PDF's jurisdiction table survives with its cells intact")
        void sanctionsTableIsRecoverable() {
            ParsedDocument document = new PdfTextExtractor().extract(
                    bundled("Sanctions-and-High-Risk-Jurisdictions-Policy.pdf"),
                    "Sanctions-and-High-Risk-Jurisdictions-Policy.pdf");

            String measures = document.sections().stream()
                    .filter(section -> section.title().startsWith("2.1"))
                    .findFirst()
                    .orElseThrow()
                    .text();

            assertThat(measures)
                    .contains("ISO-2 | Jurisdiction | Regime")
                    .contains("IR | Iran | Comprehensive - UN, EU, OFAC, SECO")
                    .contains("RU | Russian Federation | Sectoral and financial - EU, OFAC, OFSI, "
                            + "SECO");
            // The run-on form this replaced: a country read as part of the previous regime.
            assertThat(measures)
                    .doesNotContain("SECO KP North Korea")
                    .doesNotContain("OFAC RU Russian Federation");
        }

        @Test
        @DisplayName("every shipped document parses into sections with usable headings")
        void everyShippedDocumentParses() {
            List<String> docx = List.of("AML-Thresholds-and-Structuring-Policy.docx",
                    "Cryptocurrency-and-Virtual-Asset-Risk-Policy.docx");
            for (String filename : docx) {
                assertShape(new DocxTextExtractor().extract(bundled(filename), filename));
            }
            String pdf = "Sanctions-and-High-Risk-Jurisdictions-Policy.pdf";
            assertShape(new PdfTextExtractor().extract(bundled(pdf), pdf));
        }

        private void assertShape(ParsedDocument document) {
            assertThat(document.title()).isNotBlank();
            assertThat(document.sections()).hasSizeGreaterThan(4).allSatisfy(section -> {
                assertThat(section.hasText()).isTrue();
                assertThat(section.title()).isNotBlank();
                assertThat(section.title().length())
                        .isLessThanOrEqualTo(HeadingHeuristics.MAX_HEADING_LENGTH);
            });
        }

        private byte[] bundled(String filename) {
            try (InputStream in = getClass().getResourceAsStream("/knowledge/" + filename)) {
                assertThat(in).as("bundled document /knowledge/%s", filename).isNotNull();
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
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
