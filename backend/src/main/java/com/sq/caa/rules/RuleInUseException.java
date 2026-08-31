package com.sq.caa.rules;

import java.util.UUID;

/**
 * A rule that historical analysis evidence still points at cannot be deleted: the
 * {@code risk_assessments} rows of past runs name the rule, and removing it would erase the record
 * of what was judged. Rendered as {@code 409 Conflict}.
 */
public class RuleInUseException extends RuntimeException {

    private final UUID ruleId;

    public RuleInUseException(UUID ruleId) {
        super("Rule " + ruleId + " is referenced by recorded risk assessments and cannot be "
                + "deleted. Deleting it would erase historical analysis evidence.");
        this.ruleId = ruleId;
    }

    public UUID ruleId() {
        return ruleId;
    }
}
