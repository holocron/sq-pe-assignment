package com.sq.caa.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sq.caa.service.ActivitySummaryService;
import com.sq.caa.service.CustomerService;
import com.sq.caa.service.TransactionService;
import com.sq.caa.web.dto.PageResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The {@code minAmount}/{@code maxAmount} filter of {@code GET /api/customers/{id}/activity}:
 * non-negative decimals only, min never above max, and every rejection is an RFC-7807 400 rather
 * than a stack trace - the same validation style as the other activity parameters.
 *
 * <p>The services are mocked; what is under test is parameter parsing and the error contract.
 */
class ActivityAmountFilterTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("50f3ac6f-0f62-5b00-8314-cf99a4f3ac35");

    private TransactionService transactionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        transactionService = mock(TransactionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CustomerController(mock(CustomerService.class),
                transactionService, mock(ActivitySummaryService.class))).build();
    }

    @Test
    @DisplayName("a valid range is passed to the service as inclusive decimal bounds")
    void forwardsAParsedRange() throws Exception {
        when(transactionService.findCustomerActivity(eq(CUSTOMER_ID), isNull(), isNull(), isNull(),
                isNull(), any(), any(), isNull(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/api/customers/{id}/activity", CUSTOMER_ID)
                        .param("minAmount", "100.50")
                        .param("maxAmount", "9999.99"))
                .andExpect(status().isOk());

        verify(transactionService).findCustomerActivity(eq(CUSTOMER_ID), isNull(), isNull(), isNull(),
                isNull(), eq(new BigDecimal("100.50")), eq(new BigDecimal("9999.99")), isNull(),
                eq(0), eq(20));
    }

    @Test
    @DisplayName("a non-numeric bound is refused as a 400 problem+json")
    void rejectsANonNumericBound() throws Exception {
        mockMvc.perform(get("/api/customers/{id}/activity", CUSTOMER_ID)
                        .param("minAmount", "lots"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(
                        "Parameter 'minAmount' must be a decimal number, was 'lots'."));

        verifyNoInteractions(transactionService);
    }

    @Test
    @DisplayName("a negative bound is refused as a 400 problem+json")
    void rejectsANegativeBound() throws Exception {
        mockMvc.perform(get("/api/customers/{id}/activity", CUSTOMER_ID)
                        .param("maxAmount", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(
                        "Parameter 'maxAmount' must not be negative, was '-1'."));

        verifyNoInteractions(transactionService);
    }

    @Test
    @DisplayName("an inverted range is refused as a 400 problem+json")
    void rejectsAnInvertedRange() throws Exception {
        mockMvc.perform(get("/api/customers/{id}/activity", CUSTOMER_ID)
                        .param("minAmount", "500")
                        .param("maxAmount", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(
                        "'minAmount' must not be greater than 'maxAmount'."));

        verifyNoInteractions(transactionService);
    }

    @Test
    @DisplayName("absent bounds stay null and leave the other filters untouched")
    void absentBoundsAreNull() throws Exception {
        when(transactionService.findCustomerActivity(eq(CUSTOMER_ID), isNull(), eq("completed"),
                isNull(), isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/api/customers/{id}/activity", CUSTOMER_ID)
                        .param("status", "completed"))
                .andExpect(status().isOk());

        verify(transactionService).findCustomerActivity(eq(CUSTOMER_ID), isNull(), eq("completed"),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
    }
}
