package com.sq.caa.agent;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One entry of the ReAct transcript stored in {@code analysis_runs.trace} and pushed live over SSE.
 *
 * <p>The JSON shape is a published contract (BUILD_SPEC section 4) and the analysis page renders it
 * directly, so it is written by hand rather than by data binding: keys are snake_case, nulls are
 * omitted, and the four types named in the spec ({@code tool_call}, {@code assistant},
 * {@code coverage_reprompt}, {@code final}) keep exactly the fields the spec shows.
 *
 * <pre>
 * {"n":1,"type":"tool_call","tool":"list_risk_rules","args":{},"result_preview":"...","ms":812}
 * </pre>
 *
 * <h2>Subject and outcome</h2>
 * <p>{@code subject} and {@code outcome} say, in one line of human language, <em>what</em> a step
 * acted on and <em>how it came out</em>. They exist because a run with twelve rules produces two
 * dozen steps whose tool names are identical - "Submit rule verdict", over and over - and the rule
 * being judged was previously visible only inside the collapsed arguments. Now that a rule condition
 * is prose and the agent's judgement is the verdict, with no engine behind it to check the result,
 * the transcript is the only evidence of how each rule was decided; it has to name the rule on the
 * face of the step.
 *
 * <p>Both are optional and are simply omitted when a step is not scoped to anything nameable, so a
 * trace persisted before they existed reads exactly as it did.
 *
 * @param n             1-based position in the transcript
 * @param type          see {@link Type}
 * @param at            when the step was recorded
 * @param tool          tool name, for {@code tool_call} steps
 * @param args          arguments the model passed, for {@code tool_call} steps
 * @param resultPreview truncated tool result, for {@code tool_call} steps
 * @param ms            wall-clock duration of the tool call
 * @param text          assistant text, or the human-readable note of a non-tool step
 * @param missing       rule names still awaiting a verdict, for {@code coverage_reprompt} steps
 * @param riskLevel     final risk band, for {@code final} steps
 * @param detail        extra machine-readable payload, e.g. the SQL a rule evaluation ran or the
 *                      rules left unjudged
 * @param subject       what this step acted on, in human terms - the rule name of a verdict, the
 *                      transaction a lookup opened, the query a search ran; null when the step is
 *                      not scoped to one thing
 * @param outcome       the one-line result of the step - "triggered +30.00 (rule 3 of 12)",
 *                      "12 rules in scope", "3 passages"; null when there is nothing to say
 */
public record TraceStep(
        int n,
        String type,
        Instant at,
        String tool,
        JsonNode args,
        String resultPreview,
        Long ms,
        String text,
        List<String> missing,
        String riskLevel,
        JsonNode detail,
        String subject,
        String outcome) {

    /** Longest tool result kept in the transcript; the full result still went to the model. */
    public static final int PREVIEW_LIMIT = 600;

    /** Longest assistant message kept in the transcript. */
    public static final int TEXT_LIMIT = 4000;

    /** Longest subject; a rule name or a transaction descriptor, never a paragraph. */
    public static final int SUBJECT_LIMIT = 160;

    /** Longest outcome; one short phrase that fits on the collapsed row. */
    public static final int OUTCOME_LIMIT = 80;

    /**
     * Longest SQL kept on a step. A rule query is a few lines; the cap only exists so a runaway
     * generation cannot bloat {@code analysis_runs.trace}.
     */
    public static final int SQL_LIMIT = 2000;

    public TraceStep {
        missing = missing == null ? null : List.copyOf(missing);
        subject = clip(subject, SUBJECT_LIMIT);
        outcome = clip(outcome, OUTCOME_LIMIT);
    }

    /**
     * The human-readable identity of one step: what it acted on, how it came out, and - for a rule
     * evaluation - the query that decided it.
     *
     * <p>Produced where the meaning is known - the tool that ran, holding the typed payload - and
     * carried to {@link AnalysisTrace} rather than re-derived by parsing the result string back out
     * of the transcript.
     *
     * <p>{@code sql} is what makes a verdict reviewable rather than merely reported. The rule's
     * condition is answered by PostgreSQL, so "triggered +30.00" is only as trustworthy as the
     * SELECT behind it; the step carries that SELECT verbatim, for the attempts that were rejected
     * as well as for the one that stuck. It is kept out of {@code outcome} because it belongs in the
     * expanded detail of the step, not on the collapsed row.
     */
    public record Note(String subject, String outcome, String sql) {

        public Note {
            subject = clip(subject, SUBJECT_LIMIT);
            outcome = clip(outcome, OUTCOME_LIMIT);
            sql = sql == null || sql.isBlank() ? null : truncate(sql, SQL_LIMIT);
        }

        /** True when the note would add nothing to the step. */
        public boolean isEmpty() {
            return subject == null && outcome == null && sql == null;
        }

        /** Null rather than an empty note, so callers can pass the result straight through. */
        public static Note of(String subject, String outcome) {
            return of(subject, outcome, null);
        }

        /** The same, carrying the SQL a rule evaluation ran. */
        public static Note of(String subject, String outcome, String sql) {
            Note note = new Note(subject, outcome, sql);
            return note.isEmpty() ? null : note;
        }
    }

    /** Step kinds. The four values named in BUILD_SPEC section 4 must not be renamed. */
    public static final class Type {

        /** Run header: model, customer and the size of the coverage set. */
        public static final String STARTED = "started";
        /** The model called one tool; the step carries the arguments and the result preview. */
        public static final String TOOL_CALL = "tool_call";
        /** Free-text reasoning the model produced alongside or instead of tool calls. */
        public static final String ASSISTANT = "assistant";
        /** The loop refused to let the model conclude because rules were still unevaluated. */
        public static final String COVERAGE_REPROMPT = "coverage_reprompt";
        /** The loop asked the model to actually submit its conclusion. */
        public static final String REPROMPT = "reprompt";
        /** The run ended with applicable rules still unjudged, so it is recorded as failed. */
        public static final String COVERAGE_FAILED = "coverage_failed";
        /**
         * The model's conclusion arrived as prose rather than through
         * {@code submit_final_assessment} and was accepted, coverage already being complete.
         */
        public static final String PROSE_FINAL = "prose_final";
        /** Terminal step: the banded risk level and the total score. */
        public static final String FINAL = "final";
        /** The run failed. */
        public static final String ERROR = "error";

        private Type() {
        }
    }

    /** Renders the step exactly in the published shape, omitting every absent field. */
    public ObjectNode toJson(JsonNodeFactory nodes) {
        ObjectNode node = nodes.objectNode();
        node.put("n", n);
        node.put("type", type);
        if (at != null) {
            node.put("at", at.toString());
        }
        if (tool != null) {
            node.put("tool", tool);
        }
        if (args != null) {
            node.set("args", args);
        }
        if (resultPreview != null) {
            node.put("result_preview", resultPreview);
        }
        if (ms != null) {
            node.put("ms", ms);
        }
        if (text != null) {
            node.put("text", text);
        }
        if (missing != null) {
            ArrayNode array = node.putArray("missing");
            missing.forEach(array::add);
        }
        if (riskLevel != null) {
            node.put("risk_level", riskLevel);
        }
        if (detail != null) {
            node.set("detail", detail);
        }
        if (subject != null) {
            node.put("subject", subject);
        }
        if (outcome != null) {
            node.put("outcome", outcome);
        }
        return node;
    }

    /** Shortens {@code text} to {@code limit} characters, marking that it was cut. */
    public static String truncate(String text, int limit) {
        if (text == null) {
            return null;
        }
        String collapsed = text.strip();
        if (collapsed.length() <= limit) {
            return collapsed;
        }
        return collapsed.substring(0, limit) + "... [" + (collapsed.length() - limit) + " more characters]";
    }

    /**
     * One line, at most {@code limit} characters.
     *
     * <p>A subject can come from administrator-authored text (a rule name) or from model-authored
     * text (a search query), so it is flattened to a single line before it reaches the transcript;
     * an over-long one is cut with an ellipsis rather than annotated the way a truncated result
     * preview is, because this string is a label.
     */
    private static String clip(String value, int limit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String collapsed = value.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= limit
                ? collapsed
                : collapsed.substring(0, limit - 1).strip() + "\u2026";
    }
}
