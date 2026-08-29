package com.sq.caa.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory brute-force brake on {@code POST /api/auth/login}.
 *
 * <p>BCrypt alone is the only cost an attacker pays for a guess, and the seeded username set is
 * small and documented, so an unauthenticated caller can otherwise grind the login endpoint for as
 * long as they like. The rule implemented here, configurable under
 * {@code caa.security.login-throttle}:
 *
 * <blockquote>{@value #DEFAULT_LIMIT_DESCRIPTION}</blockquote>
 *
 * <p>Counting is per <em>username + client address</em> pair, which is the pragmatic middle ground:
 * per-account alone lets anyone lock a colleague out of the application from the outside, per-address
 * alone punishes everyone behind one NAT or dev proxy. A successful sign-in clears the pair
 * immediately, so a user who mistypes and then gets it right is never delayed.
 *
 * <p>Scope, stated plainly: single instance, memory only, cleared by a restart, and the address is
 * the socket peer - {@code X-Forwarded-For} is not trusted because nothing in this deployment sets
 * it and honouring it would hand the attacker the key. This is a demo-grade brake, not a WAF.
 *
 * <p>Built by {@code SecurityConfig} rather than component-scanned, so the clock stays an explicit
 * constructor argument and the lockout arithmetic can be tested without sleeping.
 */
public class LoginThrottle {

    /** The documented limit, quoted in the javadoc above and pinned against {@code application.yml}. */
    static final String DEFAULT_LIMIT_DESCRIPTION =
            "5 failed attempts for one username from one address within 15 minutes "
                    + "lock that pair out for 5 minutes";

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private static final String UNKNOWN_ADDRESS = "unknown";

    private final LoginThrottleProperties properties;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginThrottle(LoginThrottleProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Refuses the attempt when the pair is locked out.
     *
     * @throws TooManyLoginAttemptsException before any credential is checked, so a locked-out caller
     *                                       cannot even use the endpoint as a BCrypt oracle
     */
    public void check(String username, String address) {
        Attempts record = attempts.get(key(username, address));
        if (record == null) {
            return;
        }
        Instant now = clock.instant();
        Duration remaining = record.remainingLockout(now);
        if (remaining != null) {
            throw new TooManyLoginAttemptsException(remaining);
        }
    }

    /** Records a failed sign-in and locks the pair out once the limit is reached. */
    public void recordFailure(String username, String address) {
        String key = key(username, address);
        Instant now = clock.instant();
        Attempts record = attempts.computeIfAbsent(key, ignored -> new Attempts());
        boolean lockedNow = record.registerFailure(now, properties.maxAttempts(), properties.window(),
                properties.lockDuration());
        if (lockedNow) {
            log.warn("Login throttle engaged for '{}' from {} after {} failed attempts; "
                            + "further attempts refused for {}.",
                    redact(username), address, properties.maxAttempts(), properties.lockDuration());
        }
        evictIfCrowded(now);
    }

    /** Forgets the pair; called on every successful sign-in. */
    public void recordSuccess(String username, String address) {
        attempts.remove(key(username, address));
    }

    /** Tracked pairs, for the test that pins the memory bound. */
    int trackedPairs() {
        return attempts.size();
    }

    private String key(String username, String address) {
        String user = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        String host = address == null || address.isBlank() ? UNKNOWN_ADDRESS : address;
        return user + "@" + host;
    }

    /**
     * Keeps the map bounded. Expired records go first; if the cap is still exceeded - which needs a
     * deliberate flood of distinct usernames - the whole map is dropped. Losing counters fails open
     * for a moment, which is the right trade for a fixed memory ceiling in a demo.
     */
    private void evictIfCrowded(Instant now) {
        if (attempts.size() <= properties.maxTrackedPairs()) {
            return;
        }
        Iterator<Map.Entry<String, Attempts>> iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isExpired(now, properties.window())) {
                iterator.remove();
            }
        }
        if (attempts.size() > properties.maxTrackedPairs()) {
            log.warn("Login throttle is tracking more than {} username/address pairs; "
                    + "clearing the counters.", properties.maxTrackedPairs());
            attempts.clear();
        }
    }

    private static String redact(String username) {
        if (username == null || username.isBlank()) {
            return "<blank>";
        }
        String trimmed = username.trim();
        return trimmed.length() <= 2 ? trimmed.charAt(0) + "*" : trimmed.charAt(0) + "***"
                + trimmed.charAt(trimmed.length() - 1);
    }

    /** Failure counter of one username/address pair. All access is synchronised on the instance. */
    private static final class Attempts {

        private int failures;
        private Instant firstFailureAt;
        private Instant lockedUntil;

        synchronized Duration remainingLockout(Instant now) {
            if (lockedUntil == null) {
                return null;
            }
            if (!now.isBefore(lockedUntil)) {
                // The lockout elapsed: the pair starts again with a clean slate.
                failures = 0;
                firstFailureAt = null;
                lockedUntil = null;
                return null;
            }
            return Duration.between(now, lockedUntil);
        }

        synchronized boolean registerFailure(Instant now, int maxAttempts, Duration window,
                Duration lockDuration) {
            if (firstFailureAt == null || firstFailureAt.plus(window).isBefore(now)) {
                failures = 0;
                firstFailureAt = now;
            }
            failures++;
            if (failures >= maxAttempts && (lockedUntil == null || lockedUntil.isBefore(now))) {
                lockedUntil = now.plus(lockDuration);
                return true;
            }
            return false;
        }

        synchronized boolean isExpired(Instant now, Duration window) {
            boolean lockElapsed = lockedUntil == null || !now.isBefore(lockedUntil);
            boolean windowElapsed = firstFailureAt == null || firstFailureAt.plus(window).isBefore(now);
            return lockElapsed && windowElapsed;
        }
    }
}
