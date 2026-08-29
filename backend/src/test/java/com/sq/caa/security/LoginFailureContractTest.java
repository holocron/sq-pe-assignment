package com.sq.caa.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.config.GlobalExceptionHandler;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;

/**
 * What an unauthenticated caller learns from a rejected sign-in.
 *
 * <p>Lives with the security tests rather than with the handler because the property under test is a
 * security property, not an error-formatting one: Spring's {@code DaoAuthenticationProvider} runs its
 * pre-authentication checks before the password is compared, so a distinct "this account has been
 * disabled" reply would confirm that a username exists to anyone submitting any password at all.
 */
class LoginFailureContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static MockHttpServletRequest loginRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    @Test
    @DisplayName("wrong password, unknown user, disabled and locked accounts are indistinguishable")
    void everyRejectedSignInLooksTheSame() {
        ResponseEntity<ProblemDetail> wrongPassword =
                handler.handleFailedLogin(new BadCredentialsException("Bad credentials"), loginRequest());
        ResponseEntity<ProblemDetail> disabled =
                handler.handleFailedLogin(new DisabledException("User is disabled"), loginRequest());
        ResponseEntity<ProblemDetail> locked =
                handler.handleFailedLogin(new LockedException("User account is locked"), loginRequest());

        for (ResponseEntity<ProblemDetail> response : java.util.List.of(wrongPassword, disabled, locked)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Unauthorized");
            assertThat(response.getBody().getDetail()).isEqualTo("Invalid username or password.");
        }
        assertThat(disabled.getBody().getDetail())
                .as("a disabled account must not be advertised to an unauthenticated caller")
                .doesNotContainIgnoringCase("disabled");
    }

    @Test
    @DisplayName("a throttled sign-in answers 429 with Retry-After")
    void throttledSignInIsTooManyRequests() {
        ResponseEntity<ProblemDetail> response = handler.handleThrottledLogin(
                new TooManyLoginAttemptsException(Duration.ofMinutes(15)), loginRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("900");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo(
                "Too many failed sign-in attempts. Try again in 900 seconds.");
        assertThat(response.getBody().getProperties()).containsEntry("retryAfterSeconds", 900L);
    }
}
