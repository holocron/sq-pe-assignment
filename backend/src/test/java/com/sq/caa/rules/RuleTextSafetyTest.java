package com.sq.caa.rules;

import static com.sq.caa.rules.RuleTestFixtures.card;
import static com.sq.caa.rules.RuleTestFixtures.evaluate;
import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Evaluation-time behaviour of the two text operators that can be turned into something the author
 * did not mean: {@code CONTAINS} with a blank needle and {@code MATCHES} with a hostile pattern.
 *
 * <p>Writes are refused by {@link RuleValidator}, but rules stored before that check existed - or
 * written straight into {@code risk_rules} - must still be reported honestly rather than quietly
 * firing on every transaction, so each case is pinned here as "false and degraded".
 */
class RuleTextSafetyTest {

    private static final Instant AT = Instant.parse("2026-08-20T09:30:00Z");

    private static Transaction merchant(String name) {
        return card("100.00", "Completed", AT, name, "7995", "Credit", true, null);
    }

    private static NodeOutcome outcome(RuleOperator operator, Object value) {
        return evaluate(RuleCondition.of("card.merchant_name", operator, value),
                merchant("CASINO ROYALE LTD"));
    }

    @Nested
    @DisplayName("a blank needle is degenerate, not a filter")
    class BlankNeedles {

        @Test
        @DisplayName("whitespace-only CONTAINS does not match everything")
        void whitespaceOnlyContainsDegrades() {
            NodeOutcome outcome = outcome(RuleOperator.CONTAINS, "   ");

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.degraded()).isTrue();
            assertThat(outcome.notes())
                    .anyMatch(note -> note.contains("CONTAINS needs a non-blank text value"));
        }

        @Test
        @DisplayName("whitespace-only NOT_CONTAINS does not match everything either")
        void whitespaceOnlyNotContainsDegrades() {
            NodeOutcome outcome = outcome(RuleOperator.NOT_CONTAINS, "\t");

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.degraded()).isTrue();
            assertThat(outcome.notes())
                    .anyMatch(note -> note.contains("NOT_CONTAINS needs a non-blank text value"));
        }

        @Test
        void emptyContainsDegradesRatherThanSilentlyNeverMatching() {
            NodeOutcome outcome = outcome(RuleOperator.CONTAINS, "");

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.degraded()).isTrue();
        }

        @Test
        void missingContainsValueDegrades() {
            NodeOutcome outcome = outcome(RuleOperator.CONTAINS, null);

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.degraded()).isTrue();
        }

        @Test
        @DisplayName("a usable needle still decides the outcome when the list also holds a blank one")
        void blankElementsOfAListAreSkipped() {
            NodeOutcome matching = outcome(RuleOperator.CONTAINS, List.of("  ", "royale"));
            assertThat(matching.matched()).isTrue();
            assertThat(matching.degraded()).isFalse();

            NodeOutcome notMatching = outcome(RuleOperator.CONTAINS, List.of("  ", "bookmaker"));
            assertThat(notMatching.matched()).isFalse();
            assertThat(notMatching.degraded()).isFalse();
        }

        @Test
        void surroundingWhitespaceIsStillTrimmedFromARealNeedle() {
            assertThat(outcome(RuleOperator.CONTAINS, "  royale  ").matched()).isTrue();
        }
    }

    @Nested
    @DisplayName("MATCHES is bounded")
    class BoundedRegex {

        @Test
        void aBlankPatternDoesNotMatchEverything() {
            NodeOutcome outcome = outcome(RuleOperator.MATCHES, "");

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.degraded()).isTrue();
            assertThat(outcome.notes())
                    .anyMatch(note -> note.contains("MATCHES needs a non-blank regular expression"));
        }

        @Test
        void aPatternLongerThanTheCapIsRefused() {
            NodeOutcome outcome = outcome(RuleOperator.MATCHES,
                    "a".repeat(Regexes.MAX_PATTERN_LENGTH + 1));

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.degraded()).isTrue();
            assertThat(outcome.notes()).anyMatch(note -> note.contains("longer than "
                    + Regexes.MAX_PATTERN_LENGTH + " characters"));
        }

        @Test
        @DisplayName("a catastrophically backtracking pattern is abandoned, not run to completion")
        void redosIsAbandonedQuickly() {
            // Eleven characters, well inside the length cap, and exponential on this engine: at 20
            // characters of input it already costs 12 million character reads and it doubles from
            // there, so without the step budget the request never comes back.
            Transaction transaction = merchant("a".repeat(30) + "!");
            RuleCondition condition =
                    RuleCondition.of("card.merchant_name", RuleOperator.MATCHES, "(.*a){20}$");

            Instant started = Instant.now();
            NodeOutcome outcome = evaluate(condition, transaction);
            Duration elapsed = Duration.between(started, Instant.now());

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.degraded()).isTrue();
            assertThat(outcome.notes()).anyMatch(note -> note.contains("abandoned after"));
            assertThat(elapsed).as("the guard must abort, not grind").isLessThan(Duration.ofSeconds(5));
        }

        @Test
        void ordinaryPatternsAreUnaffectedByTheGuard() {
            assertThat(outcome(RuleOperator.MATCHES, "(casino|betting|forex|mixer)").matched()).isTrue();
            assertThat(outcome(RuleOperator.MATCHES, "^royale").matched()).isFalse();
            assertThat(outcome(RuleOperator.MATCHES, "(casino|betting)").degraded()).isFalse();
        }

        @Test
        @DisplayName("the compiled-pattern cache is bounded, whatever is thrown at it")
        void patternCacheStaysBounded() {
            for (int i = 0; i < Regexes.MAX_CACHED_PATTERNS * 3; i++) {
                assertThat(Regexes.compile("ad-hoc-draft-" + i + "-[0-9]+")).isPresent();
            }

            assertThat(Regexes.cachedPatternCount()).isLessThanOrEqualTo(Regexes.MAX_CACHED_PATTERNS);
        }

        @Test
        void anOverlongPatternIsNeverCompiledOrCached() {
            assertThat(Regexes.compile("b".repeat(Regexes.MAX_PATTERN_LENGTH + 1))).isEmpty();
            assertThat(Regexes.cachedPatternCount()).isLessThanOrEqualTo(Regexes.MAX_CACHED_PATTERNS);
        }
    }
}
