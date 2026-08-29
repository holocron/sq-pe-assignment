package com.sq.caa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The brute-force brake on {@code POST /api/auth/login}.
 *
 * <p>Documented limit under test: five failed attempts for one username from one address inside the
 * window lock that pair out for the lock duration, and the refusal happens before the password is
 * looked at.
 */
class LoginThrottleTest {

    private static final String USER = "admin";
    private static final String ADDRESS = "203.0.113.7";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-20T12:00:00Z"));
    private final LoginThrottleProperties properties =
            new LoginThrottleProperties(5, Duration.ofMinutes(15), Duration.ofMinutes(5), 1000);
    private final LoginThrottle throttle = new LoginThrottle(properties, clock);

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            throttle.recordFailure(USER, ADDRESS);
        }
    }

    @Test
    @DisplayName("attempts below the limit are let through")
    void allowsAttemptsUnderTheLimit() {
        fail(4);

        assertThatCode(() -> throttle.check(USER, ADDRESS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the fifth failure locks the username/address pair out")
    void locksOutAtTheLimit() {
        fail(5);

        assertThatExceptionOfType(TooManyLoginAttemptsException.class)
                .isThrownBy(() -> throttle.check(USER, ADDRESS))
                .satisfies(e -> assertThat(e.retryAfterSeconds()).isEqualTo(5 * 60));
    }

    @Test
    @DisplayName("a correct password clears the counter, so a mistyping user is never delayed")
    void successResetsTheCounter() {
        fail(4);
        throttle.recordSuccess(USER, ADDRESS);
        fail(4);

        assertThatCode(() -> throttle.check(USER, ADDRESS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the lockout expires on its own")
    void lockoutExpires() {
        fail(5);
        assertThatExceptionOfType(TooManyLoginAttemptsException.class)
                .isThrownBy(() -> throttle.check(USER, ADDRESS));

        clock.advance(Duration.ofMinutes(4));
        assertThatExceptionOfType(TooManyLoginAttemptsException.class)
                .isThrownBy(() -> throttle.check(USER, ADDRESS));

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThatCode(() -> throttle.check(USER, ADDRESS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("failures spread wider than the window never accumulate into a lockout")
    void failuresOutsideTheWindowAreForgotten() {
        for (int i = 0; i < 10; i++) {
            throttle.recordFailure(USER, ADDRESS);
            clock.advance(Duration.ofMinutes(16));
            assertThatCode(() -> throttle.check(USER, ADDRESS)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("one address cannot lock an account out for everyone else")
    void otherPairsAreUnaffected() {
        fail(5);

        assertThatCode(() -> throttle.check(USER, "198.51.100.4")).doesNotThrowAnyException();
        assertThatCode(() -> throttle.check("operator1", ADDRESS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the username is matched case-insensitively and untrimmed input cannot dodge the brake")
    void usernameIsNormalised() {
        fail(5);

        assertThatExceptionOfType(TooManyLoginAttemptsException.class)
                .isThrownBy(() -> throttle.check("  ADMIN ", ADDRESS));
    }

    @Test
    @DisplayName("a flood of distinct usernames cannot grow the map without bound")
    void trackedPairsStayBounded() {
        LoginThrottle bounded = new LoginThrottle(
                new LoginThrottleProperties(5, Duration.ofMinutes(15), Duration.ofMinutes(5), 50), clock);

        for (int i = 0; i < 500; i++) {
            bounded.recordFailure("user-" + i, ADDRESS);
        }

        assertThat(bounded.trackedPairs()).isLessThanOrEqualTo(51);
    }

    @Test
    @DisplayName("the limit in application.yml is the limit this test proves, and the one we document")
    void configuredLimitMatchesTheDocumentedOne() throws Exception {
        String config;
        try (var stream = LoginThrottleTest.class.getResourceAsStream("/application.yml")) {
            assertThat(stream).as("application.yml must be on the test classpath").isNotNull();
            config = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        assertThat(config).contains("max-attempts: " + properties.maxAttempts())
                .contains("window: 15m")
                .contains("lock-duration: 5m");
        assertThat(LoginThrottle.DEFAULT_LIMIT_DESCRIPTION)
                .isEqualTo("5 failed attempts for one username from one address within 15 minutes "
                        + "lock that pair out for 5 minutes");
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
