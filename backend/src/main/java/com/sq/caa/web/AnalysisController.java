package com.sq.caa.web;

import com.sq.caa.security.SecurityUtils;
import com.sq.caa.service.RiskAnalysisService;
import com.sq.caa.web.dto.AnalysisDtos.AnalysisAccepted;
import com.sq.caa.web.dto.AnalysisDtos.AnalysisResult;
import com.sq.caa.web.dto.AnalysisDtos.AnalysisSummary;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The AI analysis API.
 *
 * <p>Both ADMIN and OPERATOR may run an analysis and read its results - reviewing customer risk is
 * the operator's job, not an administrative one.
 *
 * <p>Starting an analysis is asynchronous by contract: the model takes minutes, so the POST returns
 * {@code 202 Accepted} with the {@code assessmentId} and the client follows the run either by
 * polling {@code GET /api/analyses/{id}} or, better, by subscribing to
 * {@code GET /api/analyses/{id}/stream}, which pushes each ReAct step as it happens.
 */
@RestController
public class AnalysisController {

    private final RiskAnalysisService analysisService;

    public AnalysisController(RiskAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /**
     * Starts an AI risk analysis of one customer.
     *
     * @return {@code 202} with the id the run will be published under
     */
    @PostMapping("/api/customers/{customerId}/analyses")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AnalysisAccepted> startAnalysis(@PathVariable UUID customerId) {
        AnalysisAccepted accepted = analysisService.start(customerId,
                SecurityUtils.currentUsernameOrSystem());
        return ResponseEntity.accepted()
                .header("Location", "/api/analyses/" + accepted.assessmentId())
                .body(accepted);
    }

    /** Analysis history of one customer, newest first. */
    @GetMapping("/api/customers/{customerId}/analyses")
    @PreAuthorize("isAuthenticated()")
    public List<AnalysisSummary> customerAnalyses(@PathVariable UUID customerId) {
        return analysisService.history(customerId);
    }

    /** One analysis: risk level, narrative, the per-rule coverage table and the ReAct trace. */
    @GetMapping("/api/analyses/{assessmentId}")
    @PreAuthorize("isAuthenticated()")
    public AnalysisResult analysis(@PathVariable UUID assessmentId) {
        return analysisService.get(assessmentId);
    }

    /**
     * Server-sent events for one analysis.
     *
     * <p>Emits a {@code status} event with the run header and then one {@code step} event per ReAct
     * step. A run still in progress streams live and the connection stays open until it finishes; a
     * run that already ended is replayed from the stored trace and the stream is closed at once.
     */
    // No "produces" on purpose: the SseEmitter return type already fixes the response to
    // text/event-stream, whereas declaring it would restrict content negotiation for the error
    // path too and turn a 404 for an unknown id into a 406.
    @GetMapping("/api/analyses/{assessmentId}/stream")
    @PreAuthorize("isAuthenticated()")
    public SseEmitter streamAnalysis(@PathVariable UUID assessmentId) {
        return analysisService.stream(assessmentId);
    }

    // ------------------------------------------------------------------
    // RFC-7807 error rendering
    // ------------------------------------------------------------------

    /** Renders the 400/404/503 raised by the service as {@code application/problem+json}. */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ProblemDetail> handleErrorResponse(ErrorResponseException exception,
            HttpServletRequest request) {
        return problem(exception.getStatusCode(), exception.getBody().getDetail(), request);
    }

    /** A path variable that could not be converted, e.g. a malformed UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Parameter '" + exception.getName()
                + "' has an invalid value: '" + exception.getValue() + "'.", request);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String detail,
            HttpServletRequest request) {
        // The stream endpoint is content-negotiated; without this an error on it would be rendered as
        // an unacceptable media type instead of as the problem document the API contract promises.
        request.removeAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setTitle(status instanceof HttpStatus resolved ? resolved.getReasonPhrase() : "Error");
        body.setDetail(detail);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
