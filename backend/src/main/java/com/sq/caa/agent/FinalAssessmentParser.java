package com.sq.caa.agent;

import com.sq.caa.domain.RiskLevel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recovers a final assessment the model wrote as prose instead of calling
 * {@code submit_final_assessment}.
 *
 * <p>Observed on a real run: with every rule already covered, the model ended its turn with a
 * paragraph containing the exact JSON it was supposed to pass to the tool. The loop could only see
 * "no tool call and no conclusion", so it re-prompted twice and paid two more round trips of a
 * multi-minute run for an assessment it already had in hand.
 *
 * <p>This parser is the pragmatic half of the fix. It reads the assistant text, finds any embedded
 * JSON object - fenced, inlined or nested inside a tool-call envelope the model hallucinated - and
 * accepts it only if it really carries a risk level and a summary. Anything vaguer is not an
 * assessment and the loop re-prompts exactly as before.
 *
 * <p>It is <b>never</b> a way around the coverage gate: {@link RiskAgentLoop} only consults it once
 * every rule of the coverage set already has a verdict.
 */
public final class FinalAssessmentParser {

    /** Objects examined per message; a chatty turn can contain a lot of braces. */
    private static final int MAX_CANDIDATES = 12;

    /** Nodes walked inside one candidate while looking for the assessment object. */
    private static final int MAX_NODES = 200;

    private static final List<String> RISK_LEVEL_KEYS =
            List.of("risk_level", "risklevel", "risk", "level", "overall_risk", "riskband");
    private static final List<String> SUMMARY_KEYS = List.of("summary", "assessment_summary");
    private static final List<String> RECOMMENDATION_KEYS =
            List.of("recommendations", "recommendation", "recommended_actions", "next_steps");

    private FinalAssessmentParser() {
    }

    /**
     * The assessment embedded in {@code text}, or {@code null} when there is none.
     *
     * <p>Requires both a risk level that names a real band and a non-empty summary: those are what
     * the tool itself requires, and accepting less would let an ordinary sentence about risk end the
     * run.
     */
    public static FinalAssessment parse(String text, JsonMapper mapper) {
        if (text == null || text.isBlank() || mapper == null) {
            return null;
        }
        for (String candidate : jsonObjects(text)) {
            JsonNode root;
            try {
                root = mapper.readTree(candidate);
            } catch (RuntimeException e) {
                continue;
            }
            FinalAssessment assessment = findAssessment(root);
            if (assessment != null) {
                return assessment;
            }
        }
        return null;
    }

    /** Breadth-first walk, so a tool-call envelope around the real arguments is transparent. */
    private static FinalAssessment findAssessment(JsonNode root) {
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_NODES) {
            JsonNode node = queue.poll();
            if (node.isObject()) {
                FinalAssessment assessment = assessmentOf(node);
                if (assessment != null) {
                    return assessment;
                }
            }
            for (JsonNode child : node) {
                if (child.isObject() || child.isArray()) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    private static FinalAssessment assessmentOf(JsonNode node) {
        RiskLevel level = riskLevel(find(node, RISK_LEVEL_KEYS));
        if (level == null) {
            return null;
        }
        String summary = flatten(find(node, SUMMARY_KEYS));
        if (summary == null) {
            return null;
        }
        return new FinalAssessment(level, summary, flatten(find(node, RECOMMENDATION_KEYS)));
    }

    /** Case- and separator-insensitive field lookup: {@code riskLevel} and {@code risk_level} agree. */
    private static JsonNode find(JsonNode node, List<String> keys) {
        for (String name : node.propertyNames()) {
            String normalised = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "")
                    .replace(" ", "");
            for (String key : keys) {
                if (normalised.equals(key.replace("_", ""))) {
                    return node.get(name);
                }
            }
        }
        return null;
    }

    private static RiskLevel riskLevel(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return RiskLevel.valueOf(node.stringValue().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A string stays a string; an array of strings becomes one line each, as the tool expects. */
    private static String flatten(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            StringJoiner lines = new StringJoiner("\n");
            for (JsonNode item : node) {
                String line = flatten(item);
                if (line != null) {
                    lines.add(line);
                }
            }
            String joined = lines.toString();
            return joined.isBlank() ? null : joined;
        }
        String value = node.isString() ? node.stringValue() : node.toString();
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Every balanced {@code {...}} in the text, outermost first. String literals are respected so a
     * brace inside a quoted summary cannot end the object early.
     */
    private static List<String> jsonObjects(String text) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < text.length() && objects.size() < MAX_CANDIDATES; index++) {
            char character = text.charAt(index);
            if (depth == 0) {
                if (character == '{') {
                    start = index;
                    depth = 1;
                }
                continue;
            }
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                objects.add(text.substring(start, index + 1));
                start = -1;
            }
        }
        return objects;
    }
}
