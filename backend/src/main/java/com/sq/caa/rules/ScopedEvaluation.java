package com.sq.caa.rules;

import java.util.List;

/**
 * Result of running one rule node over the in-scope transactions of one batch.
 *
 * @param evaluatedCount how many transactions were actually tested
 * @param matches        every transaction that matched, newest first
 * @param degraded       true when at least one condition could not be evaluated as written
 * @param notes          de-duplicated degradation reasons
 */
public record ScopedEvaluation(int evaluatedCount, List<RuleMatch> matches, boolean degraded,
        List<String> notes) {

    public ScopedEvaluation {
        matches = matches == null ? List.of() : List.copyOf(matches);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public int matchedCount() {
        return matches.size();
    }

    public boolean triggered() {
        return !matches.isEmpty();
    }

    static ScopedEvaluation empty() {
        return new ScopedEvaluation(0, List.of(), false, List.of());
    }
}
