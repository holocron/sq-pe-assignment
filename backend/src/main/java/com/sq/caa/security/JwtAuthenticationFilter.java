package com.sq.caa.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the bearer token off every request and, when it verifies, populates the
 * {@link SecurityContextHolder} for the rest of the chain.
 *
 * <p>A bad or missing token is never an error here: the filter simply leaves the
 * context empty and lets {@link JwtAuthenticationEntryPoint} produce the 401. The
 * reason is stashed on the request so the problem detail can be specific
 * ("expired" versus "not valid").
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Request attribute holding a human-readable reason why authentication did not
     * happen. Read by {@link JwtAuthenticationEntryPoint}.
     */
    static final String FAILURE_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".failure";

    /**
     * {@code EventSource} cannot set request headers, so the SSE endpoint also
     * accepts the token as a query parameter. Restricted to that one path.
     */
    private static final Pattern STREAM_PATH = Pattern.compile("/api/analyses/[^/]+/stream/?");

    private static final String TOKEN_PARAMETER = "token";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;
    private final WebAuthenticationDetailsSource detailsSource = new WebAuthenticationDetailsSource();

    public JwtAuthenticationFilter(JwtService jwtService, AppUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request, token);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            Claims claims = jwtService.parseClaims(token);
            AppUserPrincipal principal = userDetailsService.loadUserByUsername(jwtService.username(claims));
            if (!principal.isEnabled()) {
                request.setAttribute(FAILURE_ATTRIBUTE, "This account has been disabled.");
                return;
            }
            var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                    principal.getAuthorities());
            authentication.setDetails(detailsSource.buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (ExpiredJwtException ex) {
            request.setAttribute(FAILURE_ATTRIBUTE, "The session has expired. Please sign in again.");
        } catch (UsernameNotFoundException ex) {
            request.setAttribute(FAILURE_ATTRIBUTE, "The account for this token no longer exists.");
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected bearer token on {} {}: {}", request.getMethod(), request.getRequestURI(),
                    ex.getMessage());
            request.setAttribute(FAILURE_ATTRIBUTE, "The authentication token is not valid.");
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        if (isEventStreamRequest(request)) {
            return request.getParameter(TOKEN_PARAMETER);
        }
        return null;
    }

    private boolean isEventStreamRequest(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod())
                && STREAM_PATH.matcher(request.getRequestURI()).matches();
    }
}
