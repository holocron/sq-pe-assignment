package com.sq.caa.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code caa.security.login-throttle.*}.
 *
 * <p>Deliberately small: this is a single-instance demo, so the counters live in memory and are lost
 * on restart. Real anti-automation belongs at the edge.
 *
 * @param maxAttempts     consecutive failures allowed for one username from one client address
 *                        before sign-in is refused without checking the password
 * @param window          how long failures accumulate; a quiet spell of this length forgets them
 * @param lockDuration    how long the pair stays locked out once {@code maxAttempts} is reached
 * @param maxTrackedPairs safety bound on the map, so an attacker rotating usernames cannot grow it
 *                        without limit
 */
@ConfigurationProperties(prefix = "caa.security.login-throttle")
public record LoginThrottleProperties(
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("15m") Duration window,
        @DefaultValue("5m") Duration lockDuration,
        @DefaultValue("10000") int maxTrackedPairs) {

    public LoginThrottleProperties {
        if (maxAttempts < 1) {
            throw new IllegalStateException("caa.security.login-throttle.max-attempts must be at least 1");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalStateException("caa.security.login-throttle.window must be positive");
        }
        if (lockDuration == null || lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalStateException("caa.security.login-throttle.lock-duration must be positive");
        }
        if (maxTrackedPairs < 1) {
            throw new IllegalStateException("caa.security.login-throttle.max-tracked-pairs must be at least 1");
        }
    }
}
