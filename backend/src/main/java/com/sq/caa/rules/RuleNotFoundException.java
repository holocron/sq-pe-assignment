package com.sq.caa.rules;

import java.util.UUID;

/** Thrown when a rule id does not exist. Mapped to 404 by the rule controller. */
public class RuleNotFoundException extends RuntimeException {

    private final UUID ruleId;

    public RuleNotFoundException(UUID ruleId) {
        super("No risk rule with id " + ruleId);
        this.ruleId = ruleId;
    }

    public UUID ruleId() {
        return ruleId;
    }
}
