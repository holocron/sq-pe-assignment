package com.sq.caa.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Group node: {@code {"op":"AND","conditions":[...]}}.
 *
 * <p>{@code NOT} negates the conjunction of its children, which makes a single-child NOT the plain
 * negation the DSL documents and keeps a multi-child NOT well defined instead of ambiguous.
 */
public record RuleGroup(LogicalOp op, List<RuleNode> conditions) implements RuleNode {

    public RuleGroup {
        if (op == null) {
            throw new IllegalArgumentException("op is required");
        }
        conditions = List.copyOf(conditions == null ? List.of() : conditions);
    }

    public static RuleGroup and(RuleNode... children) {
        return new RuleGroup(LogicalOp.AND, List.of(children));
    }

    public static RuleGroup or(RuleNode... children) {
        return new RuleGroup(LogicalOp.OR, List.of(children));
    }

    public static RuleGroup not(RuleNode... children) {
        return new RuleGroup(LogicalOp.NOT, List.of(children));
    }

    @Override
    public int nodeCount() {
        int count = 1;
        for (RuleNode child : conditions) {
            count += child.nodeCount();
        }
        return count;
    }

    @Override
    public int depth() {
        int deepest = 0;
        for (RuleNode child : conditions) {
            deepest = Math.max(deepest, child.depth());
        }
        return deepest + 1;
    }

    @Override
    public List<String> referencedFields() {
        Set<String> fields = new LinkedHashSet<>();
        for (RuleNode child : conditions) {
            fields.addAll(child.referencedFields());
        }
        return new ArrayList<>(fields);
    }
}
