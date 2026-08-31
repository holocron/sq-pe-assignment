package com.sq.caa.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sq.caa.agent.AgentProperties;
import com.sq.caa.agent.AnalysisExecutor;
import com.sq.caa.agent.AnalysisStreamRegistry;
import com.sq.caa.agent.ReActRiskAgent;
import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.RiskLevel;
import com.sq.caa.repository.AnalysisRunRepository;
import com.sq.caa.repository.RiskAssessmentRepository;
import com.sq.caa.repository.projection.AnalysisRunSummary;
import com.sq.caa.web.AnalysisController.AnalysisHistorySort;
import com.sq.caa.web.dto.AnalysisDtos.AnalysisSummary;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The history ordering of {@link RiskAnalysisService#history(UUID, AnalysisHistorySort)}:
 * whitelisted fields only, nulls always last, enum severity order for {@code riskLevel}.
 */
class AnalysisHistorySortTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("50f3ac6f-0f62-5b00-8314-cf99a4f3ac35");

    private AnalysisRunRepository analysisRuns;
    private RiskAnalysisService service;

    @BeforeEach
    void setUp() {
        analysisRuns = mock(AnalysisRunRepository.class);
        CustomerService customers = mock(CustomerService.class);
        service = new RiskAnalysisService(
                mock(ReActRiskAgent.class),
                mock(AnalysisStreamRegistry.class),
                analysisRuns,
                mock(RiskAssessmentRepository.class),
                customers,
                mock(RiskRuleService.class),
                JsonMapper.builder().build(),
                mock(AgentProperties.class),
                mock(AnalysisExecutor.class),
                mock(PlatformTransactionManager.class),
                mock(EntityManagerFactory.class));
    }

    @Test
    @DisplayName("no sort keeps the repository order, newest first")
    void defaultIsNewestFirst() {
        Instant older = Instant.parse("2026-08-01T10:00:00Z");
        Instant newer = Instant.parse("2026-08-20T10:00:00Z");
        stubRuns(List.of(run(newer, "5.00", RiskLevel.MEDIUM), run(older, "30.00", RiskLevel.HIGH)));

        List<AnalysisSummary> history = service.history(CUSTOMER_ID, null);

        assertEquals(newer, history.get(0).createdAt());
        assertEquals(older, history.get(1).createdAt());
    }

    @Test
    @DisplayName("totalScore orders numerically, ascending and descending, nulls last either way")
    void sortsByTotalScore() {
        AnalysisRunSummary low = run(Instant.parse("2026-08-01T10:00:00Z"), "5.00", RiskLevel.LOW);
        AnalysisRunSummary high = run(Instant.parse("2026-08-02T10:00:00Z"), "65.00", RiskLevel.HIGH);
        AnalysisRunSummary running = run(Instant.parse("2026-08-03T10:00:00Z"), null, null);
        stubRuns(List.of(running, high, low));

        List<AnalysisSummary> descending =
                service.history(CUSTOMER_ID, new AnalysisHistorySort("totalScore", true));
        assertEquals(java.util.Arrays.asList("65.00", "5.00", null),
                descending.stream().map(row -> row.totalScore() == null ? null
                        : row.totalScore().toPlainString()).toList());

        List<AnalysisSummary> ascending =
                service.history(CUSTOMER_ID, new AnalysisHistorySort("totalScore", false));
        assertEquals(java.util.Arrays.asList("5.00", "65.00", null),
                ascending.stream().map(row -> row.totalScore() == null ? null
                        : row.totalScore().toPlainString()).toList());
    }

    @Test
    @DisplayName("riskLevel orders by severity, CRITICAL above LOW, not alphabetically")
    void sortsByRiskLevelSeverity() {
        AnalysisRunSummary low = run(Instant.parse("2026-08-01T10:00:00Z"), "5.00", RiskLevel.LOW);
        AnalysisRunSummary critical = run(Instant.parse("2026-08-02T10:00:00Z"), "90.00",
                RiskLevel.CRITICAL);
        stubRuns(List.of(low, critical));

        List<AnalysisSummary> history =
                service.history(CUSTOMER_ID, new AnalysisHistorySort("riskLevel", true));

        assertEquals(List.of(RiskLevel.CRITICAL, RiskLevel.LOW),
                history.stream().map(AnalysisSummary::riskLevel).toList());
    }

    @Test
    @DisplayName("startedAt is the client-facing alias of createdAt")
    void startedAtSortsByCreatedAt() {
        AnalysisRunSummary older = run(Instant.parse("2026-08-01T10:00:00Z"), "5.00", RiskLevel.LOW);
        AnalysisRunSummary newer = run(Instant.parse("2026-08-20T10:00:00Z"), "65.00", RiskLevel.HIGH);
        stubRuns(List.of(newer, older));

        List<AnalysisSummary> history =
                service.history(CUSTOMER_ID, new AnalysisHistorySort("startedAt", false));

        assertEquals(older.getCreatedAt(), history.get(0).createdAt());
        assertEquals(newer.getCreatedAt(), history.get(1).createdAt());
    }

    // ------------------------------------------------------------------

    private void stubRuns(List<AnalysisRunSummary> runs) {
        when(analysisRuns.findSummaries(CUSTOMER_ID)).thenReturn(runs);
    }

    private static AnalysisRunSummary run(Instant createdAt, String totalScore, RiskLevel level) {
        AnalysisRunSummary row = mock(AnalysisRunSummary.class, Mockito.RETURNS_SMART_NULLS);
        when(row.getAssessmentId()).thenReturn(UUID.randomUUID());
        when(row.getCustomerId()).thenReturn(CUSTOMER_ID);
        when(row.getCustomerFirstName()).thenReturn("Ada");
        when(row.getCustomerLastName()).thenReturn("L.");
        when(row.getStatus()).thenReturn(AnalysisStatus.COMPLETED);
        when(row.getRiskLevel()).thenReturn(level);
        when(row.getTotalScore()).thenReturn(totalScore == null ? null : new BigDecimal(totalScore));
        when(row.getRulesTotal()).thenReturn(4);
        when(row.getRulesEvaluated()).thenReturn(4);
        when(row.getCoverageComplete()).thenReturn(true);
        when(row.getCreatedAt()).thenReturn(createdAt);
        return row;
    }
}
