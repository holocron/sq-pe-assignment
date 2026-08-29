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
        ParsedSection section = new ParsedSection(0, 1, "3. Velocity", longBody(60));

        List<TextChunk> chunks = chunker.chunkSection(section, 7);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.sectionTitle()).isEqualTo("3. Velocity");
            assertThat(chunk.content()).startsWith("3. Velocity");
            assertThat(chunk.windowCount()).isEqualTo(chunks.size());
            assertThat(chunk.tokenEstimate()).isLessThanOrEqualTo(chunker.targetTokens() + 40);
        });
        assertThat(chunks).extracting(TextChunk::chunkIndex)
                .containsExactlyElementsOf(IntStream.range(7, 7 + chunks.size()).boxed().toList());

        // Consecutive windows must share text, otherwise a statement on the seam is lost.
        assertThat(overlaps(chunks.get(0).content(), chunks.get(1).content())).isTrue();
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

    private static String longBody(int paragraphs) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            text.append("Paragraph ").append(i)
                    .append(" describes a monitoring control that the compliance team applies to "
                            + "payment activity in the reporting window.\n\n");
        }
        return text.toString().strip();
    }

    /** True when the tail of the first window reappears at the head of the second. */
    private static boolean overlaps(String first, String second) {
        String[] firstWords = first.split("\\s+");
        String probe = String.join(" ",
                List.of(firstWords[firstWords.length - 4], firstWords[firstWords.length - 3],
                        firstWords[firstWords.length - 2], firstWords[firstWords.length - 1]));
        return second.contains(probe);
    }
}
