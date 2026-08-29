package com.sq.caa.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Result of evaluating one DSL node against one transaction.
 *
 * @param matched     whether the node held
 * @param degraded    whether anything in this subtree could not be evaluated as written
 * @param explanation the node rendered with its outcome, e.g. {@code amount=15000 GT 10000 => true}
 * @param notes       de-duplicated degradation reasons collected from this subtree
 */
public record NodeOutcome(boolean matched, boolean degraded, String explanation, List<String> notes) {

    public NodeOutcome {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    static NodeOutcome of(boolean matched, String explanation) {
        return new NodeOutcome(matched, false, explanation, List.of());
    }

    static NodeOutcome degraded(String explanation, String note) {
        return new NodeOutcome(false, true, explanation, List.of(note));
    }

    static List<String> merge(List<NodeOutcome> outcomes) {
        Set<String> merged = new LinkedHashSet<>();
        for (NodeOutcome outcome : outcomes) {
            merged.addAll(outcome.notes());
        }
        return new ArrayList<>(merged);
    }
}
