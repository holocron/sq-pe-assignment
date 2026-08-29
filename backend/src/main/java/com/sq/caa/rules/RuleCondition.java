package com.sq.caa.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Leaf node: {@code {"field":"amount","operator":"GT","value":10000}}.
 *
 * <p>{@code value} is normalised at parse time to {@code BigDecimal}, {@code String},
 * {@code Boolean}, {@code List<Object>} or {@code null}, so the evaluator never sees raw JSON.
 */
public record RuleCondition(String field, RuleOperator operator, Object value) implements RuleNode {

    public RuleCondition {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field is required");
        }
        if (operator == null) {
            throw new IllegalArgumentException("operator is required");
        }
        field = field.trim();
        if (value instanceof List<?> list) {
            // Defensive copy that tolerates null elements; the validator rejects them on write.
            value = Collections.unmodifiableList(new ArrayList<>(list));
        }
    }

    public static RuleCondition of(String field, RuleOperator operator, Object value) {
        return new RuleCondition(field, operator, value);
    }

    public static RuleCondition of(String field, RuleOperator operator) {
        return new RuleCondition(field, operator, null);
    }

    @Override
    public int nodeCount() {
        return 1;
    }

    @Override
    public int depth() {
        return 1;
    }

    @Override
    public List<String> referencedFields() {
        return List.of(field);
    }
}
