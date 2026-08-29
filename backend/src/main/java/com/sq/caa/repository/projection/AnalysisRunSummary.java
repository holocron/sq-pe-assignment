package com.sq.caa.repository.projection;

import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Lightweight view of an analysis run: everything the history list needs, without the trace. */
public interface AnalysisRunSummary {

    UUID getAssessmentId();

    UUID getCustomerId();

    String getCustomerFirstName();

    String getCustomerLastName();

    AnalysisStatus getStatus();

    RiskLevel getRiskLevel();

    BigDecimal getTotalScore();

    int getRulesTotal();

    int getRulesEvaluated();

    boolean getCoverageComplete();

    String getRequestedBy();

    Instant getCreatedAt();

    Instant getCompletedAt();
}
