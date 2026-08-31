package com.sq.caa.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The activity-sort whitelist: only the documented fields may order
 * {@code GET /api/customers/{id}/activity}, and everything else is a 400 - never an ORDER BY.
 */
class ActivitySortTest {

    @Test
    @DisplayName("a whitelisted field and direction become a Sort with a stable tie-breaker")
    void parsesAWhitelistedSort() {
        Sort sort = CustomerController.parseActivitySort("amount,desc");

        assertEquals(Sort.Order.desc("amount"), sort.getOrderFor("amount"));
        assertEquals(Sort.Order.asc("transactionId"), sort.getOrderFor("transactionId"),
                "the id tie-breaker keeps paging stable for equal amounts");
    }

    @Test
    @DisplayName("every documented field is accepted, ascending by default")
    void acceptsEveryWhitelistedField() {
        for (String field : new String[] {"amount", "createdAt", "status", "activityType"}) {
            Sort sort = CustomerController.parseActivitySort(field);
            assertEquals(Sort.Order.asc(field), sort.getOrderFor(field));
        }
    }

    @Test
    @DisplayName("an unknown field is refused as 400 rather than smuggled into an ORDER BY")
    void rejectsAnUnknownField() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> CustomerController.parseActivitySort("trace;DROP TABLE transactions,desc"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("Unknown sort field"));
    }

    @Test
    @DisplayName("a malformed direction is refused as 400")
    void rejectsABadDirection() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> CustomerController.parseActivitySort("amount,sideways"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    @DisplayName("no sort leaves the service default in place")
    void absentSortIsNull() {
        assertEquals(null, CustomerController.parseActivitySort(null));
        assertEquals(null, CustomerController.parseActivitySort("  "));
    }
}
