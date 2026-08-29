package com.sq.caa.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Section-first chunking, and the windowing that keeps an oversized section embeddable. */
class SectionChunkerTest {

    private final SectionChunker chunker = new SectionChunker(200, 40);

    @Test
    @DisplayName("a section that fits the budget becomes exactly one chunk, headed by its title")
    void shortSectionBecomesOneChunk() {
        ParsedSection section = new ParsedSection(0, 2, "2. Reporting thresholds",
                "A payment of 10,000 USD or more must be reported within one business day.");

        List<TextChunk> chunks = chunker.chunkSection(section, 0);

        assertThat(chunks).hasSize(1);
        TextChunk chunk = chunks.get(0);
        assertThat(chunk.sectionTitle()).isEqualTo("2. Reporting thresholds");
        assertThat(chunk.windowCount()).isEqualTo(1);
        assertThat(chunk.content()).startsWith("2. Reporting thresholds")
                .contains("10,000 USD or more");
    }

    @Test
    @DisplayName("chunks never span two sections and their indexes run continuously")
    void chunkIndexesRunAcrossSections() {
        ParsedDocument document = new ParsedDocument("Policy", List.of(
                new ParsedSection(0, 1, "One", "Alpha bravo charlie."),
                new ParsedSection(1, 1, "Two", "Delta echo foxtrot."),
                new ParsedSection(2, 1, "Three", "Golf hotel india.")));

        List<TextChunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(TextChunk::chunkIndex).containsExactly(0, 1, 2);
        assertThat(chunks).extracting(TextChunk::sectionTitle).containsExactly("One", "Two", "Three");
        assertThat(chunks.get(1).content()).doesNotContain("Alpha").doesNotContain("Golf");
    }

    @Test
    @DisplayName("an oversized section is cut into overlapping windows that share its title")
    void longSectionIsWindowedWithOverlap() {
        String heading = "3. Velocity";
        ParsedSection section = new ParsedSection(0, 1, heading, shortParagraphs(60));

        List<TextChunk> chunks = chunker.chunkSection(section, 7);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.sectionTitle()).isEqualTo(heading);
            assertThat(chunk.content()).startsWith(heading);
            assertThat(chunk.windowCount()).isEqualTo(chunks.size());
            assertThat(chunk.tokenEstimate()).isLessThanOrEqualTo(chunker.targetTokens() + 40);
        });
        assertThat(chunks).extracting(TextChunk::chunkIndex)
                .containsExactlyElementsOf(IntStream.range(7, 7 + chunks.size()).boxed().toList());

        // Every seam, not just the first: the text ending window n must literally open window n+1.
        for (int window = 0; window + 1 < chunks.size(); window++) {
            String shared = sharedSeam(chunks.get(window).content(),
                    chunks.get(window + 1).content(), heading);
            assertThat(shared)
                    .as("text shared across the seam between window %d and %d", window, window + 1)
                    .isNotBlank();
        }
    }

    /**
     * The regression the vacuous predecessor of {@link #sharedSeam} could not see: policy prose is
     * written in paragraphs far larger than the overlap budget, and whole-unit overlap silently
     * collapses to nothing for every one of them.
     */
    @Test
    @DisplayName("overlap survives paragraphs larger than the whole overlap budget")
    void overlapSurvivesParagraphsBiggerThanTheOverlapBudget() {
        SectionChunker production = new SectionChunker(SectionChunker.DEFAULT_TARGET_TOKENS,
                SectionChunker.DEFAULT_OVERLAP_TOKENS);
        String heading = "4.2 Structuring indicators";
        // 430 characters per paragraph - the length of the paragraphs in the shipped sample
        // policies, and about 115 estimated tokens, i.e. comfortably over the 100-token overlap.
        ParsedSection section = new ParsedSection(0, 2, heading, longParagraphs(12, 430));

        List<TextChunk> chunks = production.chunkSection(section, 0);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (int window = 0; window + 1 < chunks.size(); window++) {
            String shared = sharedSeam(chunks.get(window).content(),
                    chunks.get(window + 1).content(), heading);
            assertThat(shared)
                    .as("text shared across the seam between window %d and %d", window, window + 1)
                    .isNotBlank();
            // The repeated text must be substantial enough to carry a statement, not one stray word.
            assertThat(TokenEstimator.estimate(shared)).isGreaterThan(20);
        }
    }

    /**
     * The negative control that proves the seam probe can fail. With the overlap configured away,
     * consecutive windows share nothing and {@link #sharedSeam} must say so - without this the
     * assertions above could be passing for the wrong reason.
     */
    @Test
    @DisplayName("with overlap switched off, consecutive windows share nothing at all")
    void zeroOverlapProducesDisjointWindows() {
        SectionChunker withoutOverlap = new SectionChunker(200, 0);
        String heading = "3. Velocity";
        ParsedSection section = new ParsedSection(0, 1, heading, shortParagraphs(60));

        List<TextChunk> chunks = withoutOverlap.chunkSection(section, 0);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(sharedSeam(chunks.get(0).content(), chunks.get(1).content(), heading)).isEmpty();
    }

    @Test
    @DisplayName("windows advance even when a single sentence is longer than the whole budget")
    void oneEnormousSentenceStillTerminates() {
        String sentence = "word ".repeat(1200).strip() + ".";
        ParsedSection section = new ParsedSection(0, 1, "Runaway", sentence);

        List<TextChunk> chunks = chunker.chunkSection(section, 0);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).noneMatch(chunk -> chunk.content().isBlank());
    }

    @Test
    @DisplayName("nothing is emitted for a section with no text")
    void blankSectionsProduceNoChunks() {
        assertThat(chunker.chunkSection(new ParsedSection(0, 1, "Empty", "   \n\t "), 0)).isEmpty();
        assertThat(chunker.chunk(new ParsedDocument("Policy", List.of()))).isEmpty();
    }

    @Test
    @DisplayName("an overlap of half a window or more is capped so windows always advance")
    void overlapIsCappedAtHalfTheWindow() {
        assertThat(new SectionChunker(200, 900).overlapTokens()).isEqualTo(100);
        assertThat(new SectionChunker(800, 100).overlapTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("an over-long section heading is capped instead of multiplying every window")
    void longHeadingsAreCapped() {
        String runaway = "Definition of a reportable instruction ".repeat(300).strip();
        ParsedSection section = new ParsedSection(0, 2, runaway, longParagraphs(6, 430));

        List<TextChunk> chunks = new SectionChunker(SectionChunker.DEFAULT_TARGET_TOKENS,
                SectionChunker.DEFAULT_OVERLAP_TOKENS).chunkSection(section, 0);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.sectionTitle().length())
                    .isLessThanOrEqualTo(HeadingHeuristics.MAX_HEADING_LENGTH);
            // Without the cap the heading alone was several times the window budget.
            assertThat(chunk.tokenEstimate())
                    .isLessThanOrEqualTo(SectionChunker.DEFAULT_TARGET_TOKENS
                            + SectionChunker.DEFAULT_OVERLAP_TOKENS);
        });
    }

    /* ------------------------------------------------------------------ */

    /** Paragraphs small enough that several fit one window. */
    private static String shortParagraphs(int paragraphs) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            text.append("Paragraph ").append(i)
                    .append(" describes a monitoring control that the compliance team applies to "
                            + "payment activity in the reporting window.\n\n");
        }
        return text.toString().strip();
    }

    /** Paragraphs of roughly {@code characters} each, the size real policy prose runs to. */
    private static String longParagraphs(int paragraphs, int characters) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            StringBuilder paragraph = new StringBuilder("Control " + i + " states that ");
            int sentence = 0;
            while (paragraph.length() < characters) {
                paragraph.append("clause ").append(i).append('.').append(sentence++)
                        .append(" requires the reviewing officer to record the beneficiary and "
                                + "retain the instruction for the statutory period. ");
            }
            text.append(paragraph.toString().strip()).append("\n\n");
        }
        return text.toString().strip();
    }

    /**
     * The exact text that ends {@code first} and opens the body of {@code second}: the longest
     * prefix of the second window's body that is also a suffix of the first window.
     *
     * <p>Deliberately exact rather than a "do these few words appear anywhere" probe. The previous
     * version of this helper took the last four words of window 0 and asked whether they appeared
     * anywhere in window 1 - and every paragraph of its fixture ended in the same four words, so it
     * matched whether or not a single character was shared. It reported overlap for windows that
     * were completely disjoint, which is why the collapse this class now guards against went
     * unnoticed. A longest-common-seam measurement cannot do that: it returns exactly the repeated
     * text, and the empty string when there is none.
     */
    private static String sharedSeam(String first, String second, String heading) {
        String body = second.substring(heading.length()).strip();
        for (int length = Math.min(body.length(), first.length()); length > 0; length--) {
            String candidate = body.substring(0, length);
            if (first.endsWith(candidate)) {
                return candidate;
            }
        }
        return "";
    }
}
