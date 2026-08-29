package com.sq.caa.rules;

import com.sq.caa.domain.RuleScope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes {@code threshold_logic} documents.
 *
 * <p>Parsing is a hand-driven walk over the Jackson tree rather than data binding, because every
 * failure has to name the exact node that is wrong - the API contract requires a 400 that points at
 * the offending condition, and a generic "cannot deserialize" would be useless to a rule author.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #parse(String)} - structural only. Used at evaluation time, where a rule that
 *       references an unknown field must still evaluate (to false, degraded) instead of exploding.
 *   <li>{@link #parseStrict(String, RuleScope)} - structural plus unknown-key rejection plus the
 *       semantic checks of {@link RuleValidator}, against the scope the rule will be evaluated with.
 *       Used on every write; the scope-less overloads validate as {@code ALL}.
 * </ul>
 */
public final class RuleParser {

    /** Deepest nesting accepted, guarding against pathological or generated rules. */
    public static final int MAX_DEPTH = 12;

    /** Largest number of nodes accepted in one rule. */
    public static final int MAX_NODES = 250;

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final String KEY_OP = "op";
    private static final String KEY_CONDITIONS = "conditions";
    private static final String KEY_FIELD = "field";
    private static final String KEY_OPERATOR = "operator";
    private static final String KEY_VALUE = "value";

    private RuleParser() {
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Structural parse of a JSON document. */
    public static RuleNode parse(String json) {
        return parse(readTree(json));
    }

    /** Structural parse of an already-read tree. A JSON string node is unwrapped and re-read. */
    public static RuleNode parse(JsonNode root) {
        return parseInternal(root, false);
    }

    /** Structural parse plus unknown-key and semantic validation. Used for writes. */
    public static RuleNode parseStrict(String json) {
        return parseStrict(readTree(json), RuleScope.ALL);
    }

    /** Structural parse plus unknown-key and semantic validation. Used for writes. */
    public static RuleNode parseStrict(JsonNode root) {
        return parseStrict(root, RuleScope.ALL);
    }

    /**
     * Structural parse plus unknown-key and semantic validation against the scope the rule will run
     * with, which is what every write path must use: a leaf naming a field of another activity type
     * can never match and is refused here rather than sitting in the table.
     */
    public static RuleNode parseStrict(String json, RuleScope scope) {
        return parseStrict(readTree(json), scope);
    }

    /** Scope-aware strict parse of an already-read tree. */
    public static RuleNode parseStrict(JsonNode root, RuleScope scope) {
        RuleNode node = parseInternal(root, true);
        RuleValidator.validate(node, scope);
        return node;
    }

    /** Reads raw JSON, translating any Jackson failure into a rule validation error. */
    public static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            throw new RuleValidationException("$", null, "rule logic is empty");
        }
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new RuleValidationException("$", abbreviate(json),
                    "rule logic is not valid JSON: " + rootMessage(e));
        }
    }

    private static RuleNode parseInternal(JsonNode root, boolean strictKeys) {
        JsonNode effective = root;
        if (effective != null && effective.isString()) {
            // Tolerate the document being delivered as a JSON string (some clients double-encode).
            effective = readTree(effective.stringValue());
        }
        RuleNode node = parseNode(effective, "$", strictKeys);
        if (node.depth() > MAX_DEPTH) {
            throw new RuleValidationException("$", compact(node),
                    "rule logic nests " + node.depth() + " levels deep, the maximum is " + MAX_DEPTH);
        }
        if (node.nodeCount() > MAX_NODES) {
            throw new RuleValidationException("$", null,
                    "rule logic has " + node.nodeCount() + " nodes, the maximum is " + MAX_NODES);
        }
        return node;
    }

    private static RuleNode parseNode(JsonNode node, String path, boolean strictKeys) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new RuleValidationException(path, null, "expected a rule node, found nothing");
        }
        if (!node.isObject()) {
            throw new RuleValidationException(path, json(node),
                    "expected a rule node object, found " + describeJsonType(node));
        }
        if (node.has(KEY_OP)) {
            return parseGroup(node, path, strictKeys);
        }
        if (node.has(KEY_FIELD) || node.has(KEY_OPERATOR)) {
            return parseCondition(node, path, strictKeys);
        }
        throw new RuleValidationException(path, json(node),
                "node is neither a group (needs 'op' and 'conditions') nor a condition "
                        + "(needs 'field' and 'operator')");
    }

    private static RuleGroup parseGroup(JsonNode node, String path, boolean strictKeys) {
        if (strictKeys) {
            rejectUnknownKeys(node, path, KEY_OP, KEY_CONDITIONS);
        }
        JsonNode opNode = node.get(KEY_OP);
        if (opNode == null || !opNode.isString()) {
            throw new RuleValidationException(path, json(node), "'op' must be a string");
        }
        LogicalOp op = LogicalOp.parse(opNode.stringValue())
                .orElseThrow(() -> new RuleValidationException(path, json(node),
                        "unknown group operator '" + opNode.stringValue() + "', expected AND, OR or NOT"));

        JsonNode conditionsNode = node.get(KEY_CONDITIONS);
        if (conditionsNode == null || !conditionsNode.isArray()) {
            throw new RuleValidationException(path, json(node),
                    "group '" + op + "' requires a 'conditions' array");
        }
        if (conditionsNode.isEmpty()) {
            throw new RuleValidationException(path, json(node),
                    "group '" + op + "' has an empty 'conditions' array; a group needs at least one child");
        }
        List<RuleNode> children = new ArrayList<>(conditionsNode.size());
        int index = 0;
        for (JsonNode child : conditionsNode) {
            children.add(parseNode(child, path + "." + KEY_CONDITIONS + "[" + index + "]", strictKeys));
            index++;
        }
        return new RuleGroup(op, children);
    }

    private static RuleCondition parseCondition(JsonNode node, String path, boolean strictKeys) {
        if (strictKeys) {
            rejectUnknownKeys(node, path, KEY_FIELD, KEY_OPERATOR, KEY_VALUE);
        }
        JsonNode fieldNode = node.get(KEY_FIELD);
        if (fieldNode == null || !fieldNode.isString() || fieldNode.stringValue().isBlank()) {
            throw new RuleValidationException(path, json(node), "condition requires a 'field' string");
        }
        JsonNode operatorNode = node.get(KEY_OPERATOR);
        if (operatorNode == null || !operatorNode.isString()) {
            throw new RuleValidationException(path, json(node),
                    "condition on '" + fieldNode.stringValue() + "' requires an 'operator' string");
        }
        RuleOperator operator = RuleOperator.parse(operatorNode.stringValue())
                .orElseThrow(() -> new RuleValidationException(path, json(node),
                        "unknown operator '" + operatorNode.stringValue() + "'"));

        Object value = toValue(node.get(KEY_VALUE), path);
        return new RuleCondition(fieldNode.stringValue(), operator, value);
    }

    private static void rejectUnknownKeys(JsonNode node, String path, String... allowed) {
        for (var entry : node.properties()) {
            boolean known = false;
            for (String key : allowed) {
                if (key.equals(entry.getKey())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new RuleValidationException(path, json(node),
                        "unexpected property '" + entry.getKey() + "', expected one of "
                                + String.join(", ", allowed));
            }
        }
    }

    private static Object toValue(JsonNode node, String path) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>(node.size());
            for (JsonNode child : node) {
                if (child.isArray() || child.isObject()) {
                    throw new RuleValidationException(path, json(node),
                            "'value' array must contain scalars only");
                }
                // A null element is kept rather than rejected here: the strict validator reports it
                // with a useful message, and the evaluator simply ignores it.
                values.add(toValue(child, path));
            }
            return Collections.unmodifiableList(values);
        }
        throw new RuleValidationException(path, json(node),
                "'value' must be a scalar or an array, found " + describeJsonType(node));
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Canonical JSON of a parsed rule; what gets stored in {@code risk_rules.threshold_logic}. */
    public static String toJson(RuleNode node) {
        return MAPPER.writeValueAsString(toJsonNode(node));
    }

    /** Canonical tree of a parsed rule; what the API hands back to the visual editor. */
    public static JsonNode toJsonNode(RuleNode node) {
        return switch (node) {
            case RuleGroup group -> {
                ObjectNode object = NODES.objectNode();
                object.put(KEY_OP, group.op().name());
                ArrayNode children = object.putArray(KEY_CONDITIONS);
                for (RuleNode child : group.conditions()) {
                    children.add(toJsonNode(child));
                }
                yield object;
            }
            case RuleCondition condition -> {
                ObjectNode object = NODES.objectNode();
                object.put(KEY_FIELD, condition.field());
                object.put(KEY_OPERATOR, condition.operator().name());
                if (!(condition.operator().isNullCheck() && condition.value() == null)) {
                    object.set(KEY_VALUE, valueToNode(condition.value()));
                }
                yield object;
            }
        };
    }

    private static JsonNode valueToNode(Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        if (value instanceof BigDecimal decimal) {
            return NODES.numberNode(decimal);
        }
        if (value instanceof Number number) {
            return NODES.numberNode(new BigDecimal(number.toString()));
        }
        if (value instanceof Boolean bool) {
            return NODES.booleanNode(bool);
        }
        if (value instanceof List<?> list) {
            ArrayNode array = NODES.arrayNode(list.size());
            for (Object element : list) {
                array.add(valueToNode(element));
            }
            return array;
        }
        return NODES.stringNode(String.valueOf(value));
    }

    /** Compact JSON of a single node, used when naming a bad node in an error. */
    static String compact(RuleNode node) {
        try {
            return toJson(node);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String json(JsonNode node) {
        try {
            return abbreviate(MAPPER.writeValueAsString(node));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String abbreviate(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        return trimmed.length() <= 400 ? trimmed : trimmed.substring(0, 400) + "...";
    }

    private static String describeJsonType(JsonNode node) {
        if (node.isArray()) {
            return "an array";
        }
        if (node.isObject()) {
            return "an object";
        }
        if (node.isNumber()) {
            return "a number";
        }
        if (node.isBoolean()) {
            return "a boolean";
        }
        if (node.isString()) {
            return "a string";
        }
        return "null";
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null) {
            return cause.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
