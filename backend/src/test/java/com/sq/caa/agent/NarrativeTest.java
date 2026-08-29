package com.sq.caa.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Model prose reaches the compliance officer's screen unaltered, so a degenerating model can damage
 * the report. These tests pin the cleaning at the boundary.
 *
 * <p>The headline case is not hypothetical: it is the literal {@code analysis_runs.recommendations}
 * value a real run of this system produced after the transcript overflowed the context window and
 * was compacted. Every offending character is built from its code point rather than pasted, because
 * the whole problem is that they are invisible in a source file.
 */
class NarrativeTest {

    private static String ch(int codePoint) {
        return String.valueOf((char) codePoint);
    }

    private static final String NBSP = ch(0x00A0);        // no-break space
    private static final String NARROW_NBSP = ch(0x202F); // narrow no-break space
    private static final String FIGURE_SPACE = ch(0x2007);
    private static final String ZERO_WIDTH = ch(0x200B);
    private static final String NUL = ch(0x0000);
    private static final String BELL = ch(0x0007);

    /** Verbatim from a completed run: a text fragment followed by ~120 no-break spaces. */
    private static final String DEGENERATE_RUN_OUTPUT =
            "1. File" + NBSP + "SARS" + NBSP + "(" + NBSP + "S" + NBSP.repeat(100)
                    + ")" + NBSP.repeat(17) + ")" + NBSP.repeat(6);

    @Test
    @DisplayName("the run that damaged the recommendations panel now renders as one readable line")
    void collapsesTheNonBreakingSpaceRunThatBrokeTheLayout() {
        String cleaned = Narrative.clean(DEGENERATE_RUN_OUTPUT);

        assertThat(cleaned).isEqualTo("1. File SARS ( S ) )");
        assertThat(cleaned).doesNotContain(NBSP);
        // The panel widened because nothing in the value gave the browser a place to wrap.
        assertThat(longestUnbreakableRun(DEGENERATE_RUN_OUTPUT)).isGreaterThan(100);
        assertThat(longestUnbreakableRun(cleaned)).isLessThan(10);
    }

    @Test
    @DisplayName("String.isBlank does not see these characters, which is why the cleaning is needed")
    void nonBreakingSpaceIsNotBlankToJava() {
        assertThat(NBSP.repeat(3).isBlank()).isFalse();
        assertThat(Narrative.clean(NBSP.repeat(3))).isNull();
    }

    @Test
    @DisplayName("text that says nothing is reported as absent so the deterministic wording is used")
    void contentFreeTextBecomesNull() {
        for (String nothing : List.of("   ", "\n\n\n", NBSP + NARROW_NBSP + FIGURE_SPACE,
                "( )", "...", "-- --", "\"\"", "[]")) {
            assertThat(Narrative.clean(nothing)).as("clean(%s)", debug(nothing)).isNull();
        }
    }

    @Test
    void nullStaysNull() {
        assertThat(Narrative.clean(null)).isNull();
    }

    @Test
    @DisplayName("every invisible space variant collapses, not just the no-break one")
    void collapsesEveryInvisibleSpaceVariant() {
        String text = "File" + NARROW_NBSP + "a" + FIGURE_SPACE + "SAR" + ZERO_WIDTH + "now";

        assertThat(Narrative.clean(text)).isEqualTo("File a SAR now");
    }

    @Test
    @DisplayName("recommendations are one action per line, so line structure survives")
    void keepsLineBreaksAndCollapsesBlankRuns() {
        String cleaned =
                Narrative.clean("  File a SAR.  \n\n\n\n   Freeze the card.\r\n\r\nRequest KYC.  ");

        assertThat(cleaned).isEqualTo("File a SAR.\n\nFreeze the card.\n\nRequest KYC.");
    }

    @Test
    @DisplayName("a newline outranks the spaces around it")
    void doesNotTurnALineBreakIntoASpace() {
        assertThat(Narrative.clean("first   \n   second")).isEqualTo("first\nsecond");
    }

    @Test
    @DisplayName("control characters cannot render and would corrupt the trace JSON")
    void dropsControlCharacters() {
        assertThat(Narrative.clean("Score" + NUL + BELL + " was 100")).isEqualTo("Score was 100");
    }

    @Test
    @DisplayName("a good narrative is passed through untouched - the model's words are the report")
    void leavesRealProseAlone() {
        String prose = "Five of twelve rules were breached, led by a EUR 185,000 SWIFT wire to RU "
                + "at 02:40 UTC.\nThe combined score of 100 bands the customer CRITICAL.";

        assertThat(Narrative.clean(prose)).isEqualTo(prose);
    }

    @Test
    @DisplayName("a weak conclusion stays visible - cleaning must never rewrite what the model said")
    void doesNotReplaceContentItMerelyDislikes() {
        assertThat(Narrative.clean("No comment.")).isEqualTo("No comment.");
        assertThat(Narrative.clean(DEGENERATE_RUN_OUTPUT)).startsWith("1. File SARS");
    }

    @Test
    @DisplayName("a runaway generation is cut, and the cut is visible")
    void capsRunawayLength() {
        String cleaned = Narrative.clean("word ".repeat(5_000));

        assertThat(cleaned).hasSizeLessThanOrEqualTo(Narrative.MAX_CHARS);
        assertThat(cleaned).endsWith(Narrative.TRUNCATION_MARKER);
    }

    @Test
    @DisplayName("every FinalAssessment is cleaned, whichever path built it")
    void finalAssessmentCleansBothNarratives() {
        FinalAssessment assessment =
                new FinalAssessment(RiskLevel.CRITICAL, NBSP.repeat(4), DEGENERATE_RUN_OUTPUT);

        // A summary that says nothing is null, which is what makes the loop fall back.
        assertThat(assessment.summary()).isNull();
        assertThat(assessment.recommendations()).isEqualTo("1. File SARS ( S ) )");
    }

    @Test
    @DisplayName("the per-rule rationale is rendered in the coverage table, so it is cleaned too")
    void ruleVerdictRationaleIsCleaned() {
        AgentRuleVerdict verdict = new AgentRuleVerdict(UUID.randomUUID(), true, null, List.of(),
                "Matched" + NBSP.repeat(5) + "five payments.", Instant.EPOCH);

        assertThat(verdict.rationale()).isEqualTo("Matched five payments.");
    }

    /** Longest stretch a browser cannot break, which is what widened the panel. */
    private static int longestUnbreakableRun(String text) {
        int longest = 0;
        int current = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\n') {
                current = 0;
            } else {
                longest = Math.max(longest, ++current);
            }
        }
        return longest;
    }

    /** Code points, so a failure message about invisible characters is readable. */
    private static String debug(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> out.append(String.format("U+%04X ", cp)));
        return out.toString().trim();
    }
}
