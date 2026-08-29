package com.sq.caa.rules;

/** Thrown when a rule name collides with an existing one. Mapped to 409 by the rule controller. */
public class DuplicateRuleNameException extends RuntimeException {

    private final String ruleName;

    public DuplicateRuleNameException(String ruleName) {
        super("A risk rule named '" + ruleName + "' already exists");
        this.ruleName = ruleName;
    }

    public String ruleName() {
        return ruleName;
    }
}
