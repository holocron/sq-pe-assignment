package com.sq.caa.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** Turns "no usable credentials" into a 401 {@code problem+json} response. */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String DEFAULT_DETAIL = "Authentication is required to access this resource.";

    private final ProblemDetailWriter problemDetailWriter;

    JwtAuthenticationEntryPoint(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        problemDetailWriter.write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized", detailFor(request));
    }

    /** Prefers the specific reason recorded by {@link JwtAuthenticationFilter}. */
    private String detailFor(HttpServletRequest request) {
        Object failure = request.getAttribute(JwtAuthenticationFilter.FAILURE_ATTRIBUTE);
        return failure instanceof String reason ? reason : DEFAULT_DETAIL;
    }
}
