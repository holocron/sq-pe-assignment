package com.sq.caa.rules;

import java.util.List;

/**
 * Value type of a catalog field. The type decides which operators the editor may offer and how the
 * evaluator coerces the right-hand side of a condition.
 */
public enum FieldType {

    NUMBER(List.of(RuleOperator.GT, RuleOperator.GTE, RuleOperator.LT, RuleOperator.LTE,
            RuleOperator.EQ, RuleOperator.NEQ, RuleOperator.BETWEEN, RuleOperator.IN,
            RuleOperator.NOT_IN, RuleOperator.IS_NULL, RuleOperator.NOT_NULL)),

    STRING(List.of(RuleOperator.EQ, RuleOperator.NEQ, RuleOperator.IN, RuleOperator.NOT_IN,
            RuleOperator.CONTAINS, RuleOperator.NOT_CONTAINS, RuleOperator.MATCHES,
            RuleOperator.IS_NULL, RuleOperator.NOT_NULL)),

    ENUM(List.of(RuleOperator.EQ, RuleOperator.NEQ, RuleOperator.IN, RuleOperator.NOT_IN,
            RuleOperator.IS_NULL, RuleOperator.NOT_NULL)),

    BOOLEAN(List.of(RuleOperator.EQ, RuleOperator.NEQ, RuleOperator.IS_NULL, RuleOperator.NOT_NULL)),

    DATETIME(List.of(RuleOperator.GT, RuleOperator.GTE, RuleOperator.LT, RuleOperator.LTE,
            RuleOperator.BETWEEN, RuleOperator.EQ, RuleOperator.NEQ, RuleOperator.IS_NULL,
            RuleOperator.NOT_NULL));

    private final List<RuleOperator> allowedOperators;

    FieldType(List<RuleOperator> allowedOperators) {
        this.allowedOperators = List.copyOf(allowedOperators);
    }

    /** Operators the editor may offer for this type, in display order. */
    public List<RuleOperator> allowedOperators() {
        return allowedOperators;
    }

    public boolean supports(RuleOperator operator) {
        return operator != null && allowedOperators.contains(operator);
    }
}
