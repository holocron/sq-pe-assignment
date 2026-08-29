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
 * @param detail        extra machine-readable payload, e.g. the rule of a disagreement
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
        JsonNode detail) {

    /** Longest tool result kept in the transcript; the full result still went to the model. */
    public static final int PREVIEW_LIMIT = 600;

    /** Longest assistant message kept in the transcript. */
    public static final int TEXT_LIMIT = 4000;

    public TraceStep {
        missing = missing == null ? null : List.copyOf(missing);
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
        /** The agent's verdict for a rule contradicted the deterministic engine. */
        public static final String DISAGREEMENT = "disagreement";
        /** A rule the agent never ruled on was completed by the deterministic engine. */
        public static final String BACKFILL = "backfill";
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
}
