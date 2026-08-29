package com.sq.caa.agent;

import java.util.List;

/**
 * Raised when the loop ran out of room before every applicable rule had a verdict.
 *
 * <p>This is the failure the coverage guarantee is made of. The agent is the only source of verdicts
 * now, so a rule it never judged stays unjudged: there is no engine left to close it quietly. Rather
 * than report an analysis that skipped a rule as complete, the run is persisted {@code FAILED},
 * keeping every verdict the agent did produce, with the rules it never reached named here.
 */
public class IncompleteRuleCoverageException extends RuntimeException {

    private final transient List<UnjudgedRule> unjudgedRules;

    public IncompleteRuleCoverageException(int rulesTotal, List<UnjudgedRule> unjudgedRules,
            String ruleNames) {
        super(unjudgedRules.size() + " of " + rulesTotal + " applicable rule(s) never received a "
                + "verdict, so this analysis is incomplete and must not be reported as finished. "
                + "Unjudged: " + ruleNames);
        this.unjudgedRules = List.copyOf(unjudgedRules);
    }

    /** The rules that were never judged, by id and name. */
    public List<UnjudgedRule> unjudgedRules() {
        return unjudgedRules;
    }
}
