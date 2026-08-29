package com.sq.caa.agent;

import java.util.UUID;

/**
 * An applicable rule the agent never returned a verdict for.
 *
 * <p>The existence of one of these is what fails a run. It is carried out of the loop so the rule
 * can be named - by id and by name - in {@code analysis_runs.error} and in the trace, rather than
 * disappearing into a coverage counter that is merely short.
 */
public record UnjudgedRule(UUID ruleId, String ruleName) {
}
