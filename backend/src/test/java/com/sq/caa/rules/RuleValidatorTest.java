package com.sq.caa.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Write-time validation of a rule, now that a condition is prose.
 *
 * <p>There is no grammar left to enforce, so the checks are about a different failure: a condition
 * that is stored happily and then cannot be judged. An empty one leaves a rule in the coverage set
 * with nothing to decide; a two-word one gets a different verdict every run; an essay crowds the
 * evidence out of the agent's context. Each of those is refused here, with a message that says what
 * to do about it.
 */
class RuleValidatorTest {

    private static final String GOOD = """
            A payment whose amount is 10,000 or more sent by SWIFT to a beneficiary bank outside \
            Switzerland. Why it matters: above that threshold the transfer must be documented.""";

    @Nested
    @DisplayName("the condition")
    class Conditions {

        @Test
        void acceptsAProperCondition() {
            assertThat(RuleValidator.normaliseCondition(GOOD)).isEqualTo(GOOD.strip());
        }

        @Test
        void isRequired() {
            assertThatThrownBy(() -> RuleValidator.normaliseCondition(null))
                    .isInstanceOf(RuleValidationException.class)
                    .hasMessageContaining("required");
        }

        @Test
        void rejectsBlankText() {
            assertThatThrownBy(() -> RuleValidator.normaliseCondition("   \n\t  "))
                    .isInstanceOf(RuleValidationException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        void rejectsSomethingTooShortToJudgeConsistently() {
            assertThatThrownBy(() -> RuleValidator.normaliseCondition("large payments"))
                    .isInstanceOf(RuleValidationException.class)
                    .hasMessageContaining("at least " + RuleValidator.MIN_CONDITION_LENGTH);
        }

        @Test
        void acceptsExactlyTheMinimumLength() {
            String shortest = "x".repeat(RuleValidator.MIN_CONDITION_LENGTH);

            assertThat(RuleValidator.normaliseCondition(shortest)).hasSize(
                    RuleValidator.MIN_CONDITION_LENGTH);
        }

        @Test
        void rejectsAnEssayThatWouldCrowdOutTheEvidence() {
            String essay = "a".repeat(RuleValidator.MAX_CONDITION_LENGTH + 1);

            assertThatThrownBy(() -> RuleValidator.normaliseCondition(essay))
                    .isInstanceOf(RuleValidationException.class)
                    .hasMessageContaining("the maximum is " + RuleValidator.MAX_CONDITION_LENGTH)
                    .hasMessageContaining("split it into two rules");
        }

        @Test
        void acceptsExactlyTheMaximumLength() {
            String longest = "a".repeat(RuleValidator.MAX_CONDITION_LENGTH);

            assertThat(RuleValidator.normaliseCondition(longest)).hasSize(
                    RuleValidator.MAX_CONDITION_LENGTH);
        }

        @Test
        @DisplayName("a pasted JSON DSL document is refused with an explanation, not stored as prose")
        void rejectsTheOldJsonDsl() {
            String dsl = "{\"op\":\"AND\",\"conditions\":[{\"field\":\"amount\","
                    + "\"operator\":\"GT\",\"value\":10000}]}";

            assertThatThrownBy(() -> RuleValidator.normaliseCondition(dsl))
                    .isInstanceOf(RuleValidationException.class)
                    .hasMessageContaining("plain English");
        }

        @Test
        @DisplayName("prose that merely mentions JSON is not mistaken for the DSL")
        void acceptsProseAboutJson() {
            String prose = "A payment whose operator field in the source system is set to SWIFT and "
                    + "whose amount is above 10,000.";

            assertThatCode(() -> RuleValidator.normaliseCondition(prose)).doesNotThrowAnyException();
        }

        @Test
        void namesTheOffendingRequestField() {
            assertThatThrownBy(() -> RuleValidator.normaliseCondition(""))
                    .isInstanceOfSatisfying(RuleValidationException.class,
                            e -> assertThat(e.field()).isEqualTo("thresholdLogic"));
        }
    }

    @Nested
    @DisplayName("normalisation")
    class Normalisation {

        @Test
        void trimsAndUnifiesLineEndings() {
            String stored = RuleValidator.normaliseCondition(
                    "  Payments above 10,000 to sanctioned countries.\r\nWhy: sanctions breach.  ");

            assertThat(stored).isEqualTo(
                    "Payments above 10,000 to sanctioned countries.\nWhy: sanctions breach.");
        }

        @Test
        @DisplayName("paragraph structure survives, because a rule that lists its parts reads better")
        void keepsBlankLinesBetweenParagraphs() {
            String stored = RuleValidator.normaliseCondition(
                    "Payments above 10,000 sent by SWIFT.\n\nWhy it matters: reporting threshold.");

            assertThat(stored).contains("\n\n");
        }

        @Test
        @DisplayName("invisible control characters are stripped rather than refused")
        void dropsControlCharacters() {
            String pasted = "Payments above 10,000 \u0007sent by SWIFT to a sanctioned country.";

            String stored = RuleValidator.normaliseCondition(pasted);

            assertThat(stored).isEqualTo(
                    "Payments above 10,000 sent by SWIFT to a sanctioned country.");
        }

        @Test
        void turnsTabsIntoSpacesSoTheTextIsRenderedTheSameEverywhere() {
            String stored = RuleValidator.normaliseCondition(
                    "Payments\tabove 10,000 sent by SWIFT to a sanctioned country.");

            assertThat(stored).doesNotContain("\t").contains("Payments above 10,000");
        }
    }

    @Nested
    @DisplayName("the rule name")
    class Names {

        @Test
        void isTrimmedAndFlattenedToOneLine() {
            assertThat(RuleValidator.normaliseName("  Structuring\n below threshold  "))
                    .isEqualTo("Structuring  below threshold");
        }

        @Test
        void isRequired() {
            assertThatThrownBy(() -> RuleValidator.normaliseName(null))
                    .isInstanceOfSatisfying(RuleValidationException.class,
                            e -> assertThat(e.field()).isEqualTo("ruleName"));
        }

        @Test
        void rejectsBlank() {
            assertThatThrownBy(() -> RuleValidator.normaliseName("   "))
                    .isInstanceOf(RuleValidationException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void rejectsAnythingLongerThanTheColumn() {
            String tooLong = "n".repeat(RuleValidator.MAX_RULE_NAME_LENGTH + 1);

            assertThatThrownBy(() -> RuleValidator.normaliseName(tooLong))
                    .isInstanceOf(RuleValidationException.class)
                    .hasMessageContaining("maximum is " + RuleValidator.MAX_RULE_NAME_LENGTH);
        }
    }

    @Nested
    @DisplayName("the weight")
    class Weights {

        @Test
        void isScaledToTheColumn() {
            assertThat(RuleValidator.normaliseWeight(new BigDecimal("30")))
                    .isEqualByComparingTo("30.00")
                    .hasToString("30.00");
            assertThat(RuleValidator.normaliseWeight(new BigDecimal("30.005")))
                    .hasToString("30.01");
        }

        @Test
        void isRequiredBecauseItIsTheCeilingOnTheAgentScore() {
            assertThatThrownBy(() -> RuleValidator.normaliseWeight(null))
                    .isInstanceOf(RuleValidationException.class)
                    .hasMessageContaining("ceiling");
        }

        @Test
        void mustStayInsideTheColumnBounds() {
            assertThatThrownBy(() -> RuleValidator.normaliseWeight(new BigDecimal("0.00")))
                    .isInstanceOf(RuleValidationException.class);
            assertThatThrownBy(() -> RuleValidator.normaliseWeight(new BigDecimal("1000.00")))
                    .isInstanceOfSatisfying(RuleValidationException.class,
                            e -> assertThat(e.field()).isEqualTo("weight"));
        }

        @Test
        void acceptsTheBoundsThemselves() {
            assertThat(RuleValidator.normaliseWeight(RuleValidator.MIN_WEIGHT))
                    .isEqualByComparingTo("0.01");
            assertThat(RuleValidator.normaliseWeight(RuleValidator.MAX_WEIGHT))
                    .isEqualByComparingTo("999.99");
        }
    }
}
