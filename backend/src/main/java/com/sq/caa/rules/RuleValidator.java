package com.sq.caa.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Write-time validation of a rule.
 *
 * <p>{@code threshold_logic} is a prompt now, not a program, so there is nothing structural left to
 * check: no field names to resolve, no operators to type-check, no unsatisfiable conjunctions to
 * refuse. What can still be checked is that the text is <em>something a model can judge</em>, and
 * that is what this class does.
 *
 * <ul>
 *   <li><b>Present.</b> A blank condition would leave the rule in the coverage set with nothing to
 *       decide, so every run would either stall on it or invent a verdict.
 *   <li><b>Long enough</b> ({@value #MIN_CONDITION_LENGTH} characters). A rule that says only
 *       "large payments" gives the model no threshold, and two runs would not agree. The bound is
 *       deliberately low - it catches the empty-ish paste, not a terse but complete sentence.
 *   <li><b>Short enough</b> ({@value #MAX_CONDITION_LENGTH} characters). Every applicable rule is
 *       carried in the agent's prompt for the whole run; a rule that is an essay crowds out the
 *       evidence and pushes the conversation towards the context limit.
 *   <li><b>Prose, not the old JSON DSL.</b> A pasted {@code {"op":"AND",...}} document is refused
 *       with a message that says what changed, because it would otherwise be stored happily and
 *       then read out to the model as gibberish.
 * </ul>
 *
 * <p>Text is normalised, not just checked: line endings are unified, control characters are dropped
 * and trailing whitespace is stripped, so the column holds exactly what the model will be shown.
 */
public final class RuleValidator {

    /** Shortest condition accepted, in characters, after normalisation. */
    public static final int MIN_CONDITION_LENGTH = 20;

    /** Longest condition accepted, in characters, after normalisation. */
    public static final int MAX_CONDITION_LENGTH = 2000;

    /** Longest rule name accepted; {@code risk_rules.rule_name} is {@code VARCHAR(160)}. */
    public static final int MAX_RULE_NAME_LENGTH = 160;

    /** Smallest weight accepted; {@code risk_rules.weight} is {@code DECIMAL(5,2)}. */
    public static final BigDecimal MIN_WEIGHT = new BigDecimal("0.01");

    /** Largest weight accepted. */
    public static final BigDecimal MAX_WEIGHT = new BigDecimal("999.99");

    private static final String FIELD_CONDITION = "thresholdLogic";
    private static final String FIELD_NAME = "ruleName";
    private static final String FIELD_WEIGHT = "weight";

    private RuleValidator() {
    }

    /**
     * Validates and normalises a rule condition.
     *
     * @return the exact text to store, which is the exact text the agent will be shown
     * @throws RuleValidationException when the text is unusable as a rule condition
     */
    public static String normaliseCondition(String thresholdLogic) {
        if (thresholdLogic == null) {
            throw new RuleValidationException(FIELD_CONDITION, "is required");
        }
        String text = normaliseText(thresholdLogic);
        if (text.isEmpty()) {
            throw new RuleValidationException(FIELD_CONDITION,
                    "is empty; a rule condition is the sentence the agent judges the customer's "
                            + "activity against, so it cannot be blank");
        }
        if (looksLikeJsonDsl(text)) {
            throw new RuleValidationException(FIELD_CONDITION,
                    "looks like the old JSON rule DSL. Rule conditions are now plain English: "
                            + "describe what to look for, with concrete thresholds and time "
                            + "windows, and why it is suspicious. The agent reads the sentence, "
                            + "fetches the data and judges it");
        }
        if (text.length() < MIN_CONDITION_LENGTH) {
            throw new RuleValidationException(FIELD_CONDITION, "is only " + text.length()
                    + " characters long; at least " + MIN_CONDITION_LENGTH + " are needed. State "
                    + "what to look for and the threshold or window that makes it suspicious - a "
                    + "condition the agent has to guess at will not be judged the same way twice");
        }
        if (text.length() > MAX_CONDITION_LENGTH) {
            throw new RuleValidationException(FIELD_CONDITION, "is " + text.length()
                    + " characters long, the maximum is " + MAX_CONDITION_LENGTH + ". Every "
                    + "applicable rule is carried in the agent's prompt for the whole analysis, so "
                    + "an over-long condition crowds out the evidence it is meant to be judged "
                    + "against; split it into two rules instead");
        }
        return text;
    }

    /** Validates and trims a rule name. Uniqueness is enforced separately, against the table. */
    public static String normaliseName(String ruleName) {
        if (ruleName == null) {
            throw new RuleValidationException(FIELD_NAME, "is required");
        }
        String text = normaliseText(ruleName).replace('\n', ' ').trim();
        if (text.isEmpty()) {
            throw new RuleValidationException(FIELD_NAME, "is blank; a rule is named by the coverage "
                    + "gate when the agent has not yet ruled on it, so it needs a name a reader "
                    + "recognises");
        }
        if (text.length() > MAX_RULE_NAME_LENGTH) {
            throw new RuleValidationException(FIELD_NAME, "is " + text.length()
                    + " characters long, the maximum is " + MAX_RULE_NAME_LENGTH);
        }
        return text;
    }

    /** Validates a weight and returns it at the scale the column stores. */
    public static BigDecimal normaliseWeight(BigDecimal weight) {
        if (weight == null) {
            throw new RuleValidationException(FIELD_WEIGHT, "is required; it is the ceiling on what "
                    + "this rule may contribute to the risk score");
        }
        BigDecimal scaled = weight.setScale(2, RoundingMode.HALF_UP);
        if (scaled.compareTo(MIN_WEIGHT) < 0 || scaled.compareTo(MAX_WEIGHT) > 0) {
            throw new RuleValidationException(FIELD_WEIGHT, "must be between " + MIN_WEIGHT + " and "
                    + MAX_WEIGHT + ", was " + scaled);
        }
        return scaled;
    }

    /**
     * Unifies line endings, drops control characters and strips trailing whitespace.
     *
     * <p>Control characters are removed rather than rejected: they arrive from copy-paste, they are
     * invisible to whoever pasted them, and refusing the write with "character U+0007" would help
     * nobody. Paragraph structure is preserved, because a rule that lists its conditions on separate
     * lines reads better both to the model and in the editor.
     */
    private static String normaliseText(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character == '\r') {
                if (index + 1 < raw.length() && raw.charAt(index + 1) == '\n') {
                    continue;
                }
                out.append('\n');
            } else if (character == '\n') {
                out.append('\n');
            } else if (character == '\t') {
                out.append(' ');
            } else if (!Character.isISOControl(character)) {
                out.append(character);
            }
        }
        return out.toString().strip();
    }

    /** A pasted DSL document: a JSON object carrying one of the keys the old grammar used. */
    private static boolean looksLikeJsonDsl(String text) {
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("\"op\"") || lower.contains("\"operator\"")
                || lower.contains("\"conditions\"");
    }
}
