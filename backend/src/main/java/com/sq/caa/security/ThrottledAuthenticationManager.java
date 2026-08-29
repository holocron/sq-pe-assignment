package com.sq.caa.security;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Wraps the real {@link AuthenticationManager} with {@link LoginThrottle}.
 *
 * <p>Sits at the authentication boundary rather than in a servlet filter for one reason: the
 * username only exists once the request body has been bound, and re-reading the body in a filter to
 * find it would be worse than this. Everything that authenticates a username and password goes
 * through this bean, so there is no way round the brake.
 */
public class ThrottledAuthenticationManager implements AuthenticationManager {

    private static final String UNKNOWN_ADDRESS = "unknown";

    private final AuthenticationManager delegate;
    private final LoginThrottle throttle;

    public ThrottledAuthenticationManager(AuthenticationManager delegate, LoginThrottle throttle) {
        this.delegate = delegate;
        this.throttle = throttle;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String username = authentication == null ? null : authentication.getName();
        String address = clientAddress();

        throttle.check(username, address);
        try {
            Authentication authenticated = delegate.authenticate(authentication);
            throttle.recordSuccess(username, address);
            return authenticated;
        } catch (AuthenticationException e) {
            throttle.recordFailure(username, address);
            throw e;
        }
    }

    /**
     * The socket peer. {@code X-Forwarded-For} is deliberately ignored: no proxy in this deployment
     * sets it, and trusting a client-supplied header would let an attacker reset their own counter
     * with every request.
     */
    private static String clientAddress() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            String remote = servletAttributes.getRequest().getRemoteAddr();
            if (remote != null && !remote.isBlank()) {
                return remote;
            }
        }
        return UNKNOWN_ADDRESS;
    }
}
