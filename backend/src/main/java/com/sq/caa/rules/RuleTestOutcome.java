package com.sq.caa.rules;

import java.util.List;

/**
 * Result of the admin "test this rule" action: the same engine, run over live data without saving
 * anything.
 *
 * @param matchedCount   transactions that matched
 * @param evaluatedCount transactions tested
 * @param customerCount  customers the draft rule was tried against
 * @param degraded       true when a condition could not be evaluated as written
 * @param notes          degradation reasons, de-duplicated
 * @param sampleMatches  a bounded sample of matches with their traces
 */
public record RuleTestOutcome(
        int matchedCount,
        int evaluatedCount,
        int customerCount,
        boolean degraded,
        List<String> notes,
        List<RuleMatch> sampleMatches) {

    /** Largest number of sample matches returned to the editor. */
    public static final int SAMPLE_LIMIT = 20;

    public RuleTestOutcome {
        notes = notes == null ? List.of() : List.copyOf(notes);
        sampleMatches = sampleMatches == null ? List.of() : List.copyOf(sampleMatches);
    }
}
