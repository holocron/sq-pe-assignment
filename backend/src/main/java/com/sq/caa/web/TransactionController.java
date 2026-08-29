package com.sq.caa.web;

import com.sq.caa.service.TransactionService;
import com.sq.caa.web.dto.TransactionDtos.TransactionView;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Transaction drill-down. Both ADMIN and OPERATOR may read.
 *
 * <p>Errors are returned as RFC-7807 {@code application/problem+json} by the handlers at the bottom
 * of this class.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /** One transaction with its CARD/PAYMENT/CRYPTO detail inlined. */
    @GetMapping("/{transactionId}")
    @PreAuthorize("isAuthenticated()")
    public TransactionView getTransaction(@PathVariable UUID transactionId) {
        return transactionService.getTransaction(transactionId);
    }

    /** Renders 404/400 raised by the service as {@code application/problem+json}. */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ProblemDetail> handleErrorResponse(ErrorResponseException exception) {
        return problem(exception.getStatusCode(), exception.getBody().getDetail());
    }

    /** A path variable that could not be converted, e.g. a malformed UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Parameter '" + exception.getName()
                + "' has an invalid value: '" + exception.getValue() + "'.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String detail) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setTitle(status instanceof HttpStatus resolved ? resolved.getReasonPhrase() : "Error");
        body.setDetail(detail);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
