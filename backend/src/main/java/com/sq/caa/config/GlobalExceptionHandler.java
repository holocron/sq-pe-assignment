package com.sq.caa.config;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place that turns exceptions into RFC-7807 {@code application/problem+json}.
 *
 * <p>Every body carries {@code status}, {@code title}, {@code detail} and
 * {@code instance}; validation failures add an {@code errors} object keyed by
 * field name. Authentication failures raised inside the servlet filter chain are
 * rendered in the same shape by {@code ProblemDetailWriter}.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} keeps Spring MVC's own
 * mappings (unreadable body, unsupported media type, unknown route, upload too
 * large, {@code ResponseStatusException}) and adds the application's on top.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String GLOBAL_ERROR_KEY = "_global";

    /* ---------------------------------------------------------------------- */
    /* Spring MVC's own mappings                                               */
    /* ---------------------------------------------------------------------- */

    /**
     * Stamps {@code instance} onto the problem details Spring MVC builds itself
     * (unknown route, unreadable body, {@code ResponseStatusException} thrown by
     * the services), so every error body has the same fields.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem && problem.getInstance() == null) {
            URI instance = instanceUri(request);
            if (instance != null) {
                problem.setInstance(instance);
            }
        }
        return response;
    }

    /* ---------------------------------------------------------------------- */
    /* Validation                                                             */
    /* ---------------------------------------------------------------------- */

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields are invalid.", request);
        problem.setProperty("errors", fieldErrors(ex.getBindingResult()));
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
            HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more values are invalid.", request);
        problem.setProperty("errors", violationErrors(ex));
        return respond(problem);
    }

    /* ---------------------------------------------------------------------- */
    /* Authentication and authorisation                                        */
    /* ---------------------------------------------------------------------- */

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class, LockedException.class})
    public ResponseEntity<ProblemDetail> handleFailedLogin(AuthenticationException ex, HttpServletRequest request) {
        log.debug("Rejected sign-in attempt on {}: {}", request.getRequestURI(), ex.getMessage());
        String detail = ex instanceof DisabledException
                ? "This account has been disabled."
                : "Invalid username or password.";
        return respond(problem(HttpStatus.UNAUTHORIZED, "Unauthorized", detail, request));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex,
            HttpServletRequest request) {
        log.debug("Authentication failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return respond(problem(HttpStatus.UNAUTHORIZED, "Unauthorized",
                "Authentication is required to access this resource.", request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.debug("Access denied on {}: {}", request.getRequestURI(), ex.getMessage());
        return respond(problem(HttpStatus.FORBIDDEN, "Forbidden",
                "Your role does not permit this operation.", request));
    }

    /* ---------------------------------------------------------------------- */
    /* Missing and conflicting data                                            */
    /* ---------------------------------------------------------------------- */

    @ExceptionHandler({EntityNotFoundException.class, NoSuchElementException.class,
            EmptyResultDataAccessException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException ex, HttpServletRequest request) {
        String detail = hasText(ex.getMessage()) ? ex.getMessage() : "The requested resource does not exist.";
        return respond(problem(HttpStatus.NOT_FOUND, "Not found", detail, request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex,
            HttpServletRequest request) {
        log.debug("Rejected request to {}: {}", request.getRequestURI(), ex.getMessage());
        String detail = hasText(ex.getMessage()) ? ex.getMessage() : "The request could not be processed.";
        return respond(problem(HttpStatus.BAD_REQUEST, "Invalid request", detail, request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex,
            HttpServletRequest request) {
        log.warn("Database constraint violated on {}", request.getRequestURI(), ex);
        return respond(problem(HttpStatus.CONFLICT, "Conflict",
                "The change conflicts with data that already exists.", request));
    }

    /* ---------------------------------------------------------------------- */
    /* Catch-all                                                               */
    /* ---------------------------------------------------------------------- */

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Server error",
                "The request could not be completed because of an unexpected error.", request));
    }

    /* ---------------------------------------------------------------------- */
    /* Helpers                                                                 */
    /* ---------------------------------------------------------------------- */

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        URI instance = instanceUri(request);
        if (instance != null) {
            problem.setInstance(instance);
        }
        return problem;
    }

    private static URI instanceUri(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? URI.create(servletRequest.getRequest().getRequestURI())
                : null;
    }

    /** Field name to messages, matching what the frontend's error parser expects. */
    private static Map<String, List<String>> fieldErrors(BindingResult bindingResult) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (FieldError fieldError : bindingResult.getFieldErrors()) {
            errors.computeIfAbsent(fieldError.getField(), key -> new ArrayList<>())
                    .add(messageOf(fieldError.getDefaultMessage()));
        }
        for (ObjectError objectError : bindingResult.getGlobalErrors()) {
            errors.computeIfAbsent(GLOBAL_ERROR_KEY, key -> new ArrayList<>())
                    .add(messageOf(objectError.getDefaultMessage()));
        }
        return errors;
    }

    private static Map<String, List<String>> violationErrors(ConstraintViolationException ex) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path = violation.getPropertyPath() == null ? GLOBAL_ERROR_KEY
                    : violation.getPropertyPath().toString();
            String field = hasText(path) ? path : GLOBAL_ERROR_KEY;
            errors.computeIfAbsent(field, key -> new ArrayList<>()).add(messageOf(violation.getMessage()));
        }
        return errors;
    }

    private static String messageOf(String message) {
        return hasText(message) ? message : "is invalid";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
