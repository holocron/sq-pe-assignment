package com.sq.caa.security;

import java.time.Duration;
import org.springframework.security.core.AuthenticationException;

/**
 * Sign-in refused by {@link LoginThrottle} before the password was even looked at.
 *
 * <p>An {@link AuthenticationException} so it travels the same path as any other failed sign-in;
 * {@code GlobalExceptionHandler} renders it as {@code 429} with a {@code Retry-After} header.
 */
public class TooManyLoginAttemptsException extends AuthenticationException {

    private static final long serialVersionUID = 1L;

    private final transient Duration retryAfter;

    public TooManyLoginAttemptsException(Duration retryAfter) {
        super("Too many failed sign-in attempts");
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }

    /** How long the caller must wait, rounded up to whole seconds and never below one. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
