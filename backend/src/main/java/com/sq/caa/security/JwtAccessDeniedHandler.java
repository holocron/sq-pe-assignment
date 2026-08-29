package com.sq.caa.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Turns "signed in, wrong role" into a 403 {@code problem+json} response.
 *
 * <p>Only reached for authenticated callers; anonymous requests go to
 * {@link JwtAuthenticationEntryPoint} instead.
 */
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemDetailWriter problemDetailWriter;

    JwtAccessDeniedHandler(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        problemDetailWriter.write(request, response, HttpStatus.FORBIDDEN, "Forbidden",
                "Your role does not permit this operation.");
    }
}
