package com.sq.caa.web;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.service.ActivitySummaryService;
import com.sq.caa.service.CustomerService;
import com.sq.caa.service.TransactionService;
import com.sq.caa.web.dto.CustomerDtos.CustomerActivitySummary;
import com.sq.caa.web.dto.CustomerDtos.CustomerDetail;
import com.sq.caa.web.dto.CustomerDtos.CustomerSummary;
import com.sq.caa.web.dto.PageResponse;
import com.sq.caa.web.dto.TransactionDtos.TransactionView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Customer read API. Both ADMIN and OPERATOR may read; every endpoint only requires authentication.
 *
 * <p>Errors are returned as RFC-7807 {@code application/problem+json} by the handlers at the bottom
 * of this class, so the contract holds regardless of any application-wide error handling.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final TransactionService transactionService;
    private final ActivitySummaryService activitySummaryService;

    public CustomerController(CustomerService customerService,
            TransactionService transactionService,
            ActivitySummaryService activitySummaryService) {
        this.customerService = customerService;
        this.transactionService = transactionService;
        this.activitySummaryService = activitySummaryService;
    }

    /**
     * Searches customers by full or partial UUID or by name (case-insensitive, partial). A blank
     * query lists every customer.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PageResponse<CustomerSummary> search(@RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return customerService.search(query, page, size);
    }

    /** One customer profile. */
    @GetMapping("/{customerId}")
    @PreAuthorize("isAuthenticated()")
    public CustomerDetail getCustomer(@PathVariable UUID customerId) {
        return customerService.getCustomer(customerId);
    }

    /** Aggregated activity of one customer: the whole dashboard payload in a single call. */
    @GetMapping("/{customerId}/summary")
    @PreAuthorize("isAuthenticated()")
    public CustomerActivitySummary getSummary(@PathVariable UUID customerId) {
        return activitySummaryService.summarise(customerId);
    }

    /**
     * Page of a customer's transactions with the per-type detail inlined.
     *
     * @param type   optional activity type: {@code CARD}, {@code PAYMENT} or {@code CRYPTO}
     * @param status optional status, matched case-insensitively
     * @param from   optional inclusive lower bound, an ISO date or timestamp
     * @param to     optional inclusive upper bound; a bare date covers the whole day
     * @param minAmount optional inclusive lower bound on {@code amount}, a non-negative decimal
     * @param maxAmount optional inclusive upper bound on {@code amount}, a non-negative decimal
     * @param sort   optional {@code <field>,<asc|desc>} - one of {@code amount}, {@code createdAt},
     *               {@code status}, {@code activityType}. Default is {@code createdAt,desc}.
     */
    @GetMapping("/{customerId}/activity")
    @PreAuthorize("isAuthenticated()")
    public PageResponse<TransactionView> getActivity(@PathVariable UUID customerId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String minAmount,
            @RequestParam(required = false) String maxAmount,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ActivityType activityType = parseActivityType(type);
        Instant fromInstant = parseInstant(from, "from", false);
        Instant toInstant = parseInstant(to, "to", true);
        BigDecimal min = parseAmount(minAmount, "minAmount");
        BigDecimal max = parseAmount(maxAmount, "maxAmount");
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'minAmount' must not be greater than 'maxAmount'.");
        }
        return transactionService.findCustomerActivity(customerId, activityType, status, fromInstant,
                toInstant, min, max, parseActivitySort(sort), page, size);
    }

    // ------------------------------------------------------------------
    // Parameter parsing - done by hand so bad input yields problem+json
    // ------------------------------------------------------------------

    private static ActivityType parseActivityType(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return ActivityType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown activity type '" + value
                    + "'. Expected one of CARD, PAYMENT, CRYPTO.");
        }
    }

    /**
     * Accepts {@code 2026-08-01T09:30:00Z}, an offset date-time, a local date-time (read as UTC) or a
     * bare {@code 2026-08-01}. A bare date resolves to the start of that day, or to its last instant
     * when it bounds the end of the window, so {@code from=X&to=X} covers exactly one day.
     */
    private static Instant parseInstant(String raw, String parameterName, boolean endOfDay) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through to the next supported shape
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through to the next supported shape
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through to the next supported shape
        }
        try {
            LocalDate date = LocalDate.parse(value);
            LocalDateTime moment = endOfDay ? date.atTime(LocalTime.MAX) : date.atStartOfDay();
            return moment.toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter '" + parameterName
                    + "' must be an ISO-8601 date (2026-08-01) or timestamp (2026-08-01T09:30:00Z), was '"
                    + value + "'.");
        }
    }

    /**
     * Parses an amount-range bound: a plain non-negative decimal, or {@code null} when absent. A
     * negative or non-numeric value is refused as 400, same as the other parameter errors.
     */
    static BigDecimal parseAmount(String raw, String parameterName) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        final BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter '" + parameterName
                    + "' must be a decimal number, was '" + value + "'.");
        }
        if (amount.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter '" + parameterName
                    + "' must not be negative, was '" + value + "'.");
        }
        return amount;
    }

    /**
     * Parses the activity sort, whitelisting the fields a client may order by so a {@code sort}
     * value can never become anything but one of those column names. Returns {@code null} when no
     * sort was given, leaving the service's default (newest first) in place.
     */
    static Sort parseActivitySort(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(",");
        String field = parts[0].trim();
        String mapped = ACTIVITY_SORT_FIELDS.get(field);
        if (mapped == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown sort field '" + field
                    + "'. Expected one of " + String.join(", ", ACTIVITY_SORT_FIELDS.keySet()) + ".");
        }
        boolean descending = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());
        if (parts.length > 1 && !descending && !"asc".equalsIgnoreCase(parts[1].trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter 'sort' direction "
                    + "must be 'asc' or 'desc', was '" + parts[1].trim() + "'.");
        }
        Sort.Order order = descending ? Sort.Order.desc(mapped) : Sort.Order.asc(mapped);
        // The id tie-breaker keeps paging stable for rows that compare equal on the sort field.
        return Sort.by(order, Sort.Order.asc("transactionId"));
    }

    /** Sortable activity fields, API name to entity attribute. Whitelist - nothing else may sort. */
    private static final Map<String, String> ACTIVITY_SORT_FIELDS = Map.of(
            "amount", "amount",
            "createdAt", "createdAt",
            "status", "status",
            "activityType", "activityType");

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------
    // RFC-7807 error rendering
    // ------------------------------------------------------------------

    /** Renders 404/400 raised by the services as {@code application/problem+json}. */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ProblemDetail> handleErrorResponse(ErrorResponseException exception) {
        return problem(exception.getStatusCode(), exception.getBody().getDetail());
    }

    /** A path variable or query parameter that could not be converted, e.g. a malformed UUID. */
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
