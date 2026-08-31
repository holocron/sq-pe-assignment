package com.sq.caa.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.service.RiskAnalysisService;
import com.sq.caa.web.AnalysisController.AnalysisHistorySort;
import com.sq.caa.web.dto.AnalysisDtos.AnalysisAccepted;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/**
 * The analysis API's cancellation and history-sort contract, with the service mocked - what is
 * pinned here is the web surface: status codes, the problem+json body and the sort whitelist.
 */
class AnalysisApiTest {

    private static final UUID ASSESSMENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CUSTOMER_ID = UUID.fromString("50f3ac6f-0f62-5b00-8314-cf99a4f3ac35");

    private RiskAnalysisService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(RiskAnalysisService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AnalysisController(service)).build();
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cancelling a running analysis answers 202 with the run id")
    void cancelAnswersAccepted() throws Exception {
        when(service.cancel(ASSESSMENT_ID))
                .thenReturn(new AnalysisAccepted(ASSESSMENT_ID, AnalysisStatus.RUNNING));

        mockMvc.perform(post("/api/analyses/{id}/cancel", ASSESSMENT_ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.assessmentId").value(ASSESSMENT_ID.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        verify(service).cancel(ASSESSMENT_ID);
    }

    @Test
    @DisplayName("cancelling a finished analysis answers 409 as problem+json")
    void cancelOfATerminalRunConflicts() throws Exception {
        when(service.cancel(ASSESSMENT_ID)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT, "Analysis " + ASSESSMENT_ID + " is already COMPLETED."));

        mockMvc.perform(post("/api/analyses/{id}/cancel", ASSESSMENT_ID))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "Analysis " + ASSESSMENT_ID + " is already COMPLETED."));
    }

    @Test
    @DisplayName("cancelling an unknown analysis answers 404 as problem+json")
    void cancelOfAnUnknownRunIsNotFound() throws Exception {
        when(service.cancel(ASSESSMENT_ID)).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Analysis " + ASSESSMENT_ID + " was not found."));

        mockMvc.perform(post("/api/analyses/{id}/cancel", ASSESSMENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ------------------------------------------------------------------
    // History sorting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the history sort is parsed and handed to the service")
    void historySortIsPassedThrough() throws Exception {
        when(service.history(eq(CUSTOMER_ID), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/customers/{id}/analyses", CUSTOMER_ID)
                        .param("sort", "totalScore,asc"))
                .andExpect(status().isOk());

        verify(service).history(eq(CUSTOMER_ID),
                eq(new AnalysisHistorySort("totalScore", false)));
    }

    @Test
    @DisplayName("no sort keeps the service default, newest first")
    void historyWithoutSortKeepsTheDefault() throws Exception {
        when(service.history(eq(CUSTOMER_ID), isNull())).thenReturn(List.of());

        mockMvc.perform(get("/api/customers/{id}/analyses", CUSTOMER_ID))
                .andExpect(status().isOk());

        verify(service).history(CUSTOMER_ID, null);
    }

    @Test
    @DisplayName("a sort field outside the whitelist is refused as 400 problem+json")
    void historyRejectsAnUnknownSortField() throws Exception {
        mockMvc.perform(get("/api/customers/{id}/analyses", CUSTOMER_ID)
                        .param("sort", "trace;DROP TABLE analysis_runs,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
                        "Unknown sort field")));
    }

    @Test
    @DisplayName("a malformed sort direction is refused as 400 problem+json")
    void historyRejectsABadDirection() throws Exception {
        mockMvc.perform(get("/api/customers/{id}/analyses", CUSTOMER_ID)
                        .param("sort", "totalScore,sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
