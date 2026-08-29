package com.sq.caa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The wiring that makes the throttle unavoidable: everything that authenticates a username and
 * password goes through this manager, so a locked-out caller never reaches the password check.
 */
class ThrottledAuthenticationManagerTest {

    private static final String ADDRESS = "203.0.113.7";

    private final AtomicInteger delegateCalls = new AtomicInteger();
    private final LoginThrottleProperties properties =
            new LoginThrottleProperties(3, Duration.ofMinutes(15), Duration.ofMinutes(5), 1000);
    private final LoginThrottle throttle = new LoginThrottle(properties, Clock.systemUTC());

    private final AuthenticationManager delegate = authentication -> {
        delegateCalls.incrementAndGet();
        if ("right".equals(authentication.getCredentials())) {
            return UsernamePasswordAuthenticationToken.authenticated(authentication.getPrincipal(),
                    null, java.util.List.of());
        }
        throw new BadCredentialsException("Bad credentials");
    };

    private final AuthenticationManager manager = new ThrottledAuthenticationManager(delegate, throttle);

    @BeforeEach
    void bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(ADDRESS);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private Authentication attempt(String password) {
        return UsernamePasswordAuthenticationToken.unauthenticated("admin", password);
    }

    @Test
    @DisplayName("guesses beyond the limit are refused without ever reaching the password check")
    void refusesFurtherGuessesWithoutTouchingTheDelegate() {
        for (int i = 0; i < 3; i++) {
            assertThatExceptionOfType(BadCredentialsException.class)
                    .isThrownBy(() -> manager.authenticate(attempt("wrong")));
        }
        assertThat(delegateCalls.get()).isEqualTo(3);

        assertThatExceptionOfType(TooManyLoginAttemptsException.class)
                .isThrownBy(() -> manager.authenticate(attempt("wrong")));
        assertThat(delegateCalls.get()).as("no BCrypt work once locked out").isEqualTo(3);
    }

    @Test
    @DisplayName("the correct password is refused too while the lockout stands")
    void lockoutAppliesToTheRightPasswordAsWell() {
        for (int i = 0; i < 3; i++) {
            assertThatExceptionOfType(BadCredentialsException.class)
                    .isThrownBy(() -> manager.authenticate(attempt("wrong")));
        }

        assertThatExceptionOfType(TooManyLoginAttemptsException.class)
                .isThrownBy(() -> manager.authenticate(attempt("right")));
    }

    @Test
    @DisplayName("a successful sign-in clears the counter")
    void successClearsTheCounter() {
        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> manager.authenticate(attempt("wrong")));
        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> manager.authenticate(attempt("wrong")));

        assertThat(manager.authenticate(attempt("right")).isAuthenticated()).isTrue();

        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> manager.authenticate(attempt("wrong")));
        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> manager.authenticate(attempt("wrong")));
    }
}
