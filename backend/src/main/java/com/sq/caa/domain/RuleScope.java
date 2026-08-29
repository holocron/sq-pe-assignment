package com.sq.caa.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Activity scope a risk rule applies to.
 *
 * <p>Backed by the native PostgreSQL enum type {@code rule_scope}. {@link #ALL} rules apply to every
 * activity type.
 */
public enum RuleScope {
    CARD,
    PAYMENT,
    CRYPTO,
    ALL;

    /** Scope that covers exactly the given activity type. */
    public static RuleScope of(ActivityType activityType) {
        return switch (activityType) {
            case CARD -> CARD;
            case PAYMENT -> PAYMENT;
            case CRYPTO -> CRYPTO;
        };
    }

    /**
     * Scopes whose rules must be evaluated for a customer that has the given activity types. Always
     * contains {@link #ALL}.
     */
    public static Set<RuleScope> coverageSetFor(Iterable<ActivityType> activityTypes) {
        Set<RuleScope> scopes = EnumSet.of(ALL);
        for (ActivityType activityType : activityTypes) {
            scopes.add(of(activityType));
        }
        return scopes;
    }

    /** Whether a rule with this scope has to be evaluated against the given activity type. */
    public boolean matches(ActivityType activityType) {
        return this == ALL || this == of(activityType);
    }
}
