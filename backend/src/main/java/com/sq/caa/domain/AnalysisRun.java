package com.sq.caa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Header record of one AI analysis. Maps {@code analysis_runs}.
 *
 * <p>Its {@link #assessmentId} is the same identifier that every {@link RiskAssessment} row of the
 * run carries, so the per-rule detail joins straight onto it.
 */
@Entity
@Table(name = "analysis_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalysisRun {

    @Id
    @Column(name = "assessment_id", nullable = false, updatable = false)
    private UUID assessmentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 20)
    private RiskLevel riskLevel;

    @Column(name = "total_score", precision = 10, scale = 2)
    private BigDecimal totalScore;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "recommendations", columnDefinition = "text")
    private String recommendations;

    /** Size of the coverage set: every rule that had to be evaluated for this customer. */
    @Column(name = "rules_total", nullable = false)
    private int rulesTotal;

    /** How many of those rules ended with an agent verdict. */
    @Column(name = "rules_evaluated", nullable = false)
    private int rulesEvaluated;

    /**
     * {@code true} only when every applicable rule was judged. A {@code COMPLETED} run always has
     * {@code true}: a run that ran out of steps with rules unjudged is stored {@code FAILED}, with
     * the verdicts it did obtain and the missing rules named in {@link #error}.
     */
    @Column(name = "coverage_complete", nullable = false)
    private boolean coverageComplete;

    @Column(name = "model", length = 120)
    private String model;

    /** Number of ReAct steps the loop consumed. */
    @Column(name = "steps", nullable = false)
    private int steps;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** Full ReAct transcript as JSON: {@code {"steps":[{"n":1,"type":"tool_call",...}]}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trace", columnDefinition = "jsonb")
    private String trace;

    /** Failure detail when {@link #status} is {@link AnalysisStatus#FAILED}. */
    @Column(name = "error", columnDefinition = "text")
    private String error;

    /** Username of the operator that requested the analysis. */
    @Column(name = "requested_by", length = 64)
    private String requestedBy;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "completed_at")
    private Instant completedAt;
}
