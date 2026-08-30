package com.sq.caa.web.dto;

import com.sq.caa.agent.RuleOutcome;
import com.sq.caa.agent.RuleVerdictSource;
import com.sq.caa.domain.AnalysisRun;
import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.repository.projection.AnalysisRunSummary;
import com.sq.caa.repository.projection.RuleEvaluationRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Request and response payloads of the AI analysis API. */
public final class AnalysisDtos {

    private AnalysisDtos() {
    }

    /** {@code 202} body of {@code POST /api/customers/{id}/analyses}. */
    public record AnalysisAccepted(UUID assessmentId, AnalysisStatus status) {
    }

    /**
     * One rule of one run, as the analysis page renders it.
     *
     * <p>Every rule of the coverage set appears here, triggered or not - that is what makes rule
     * coverage visible. Every verdict on this list was computed by PostgreSQL: the agent wrote a
     * SELECT expressing the rule's condition, {@code triggered} is whether that query returned rows
     * and {@code score} is the rule's weight or {@code 0.00}. {@code rationale} is the agent's
     * account of what its query looked for, and {@code sql} is the statement that actually ran - the
     * two together are what let a reviewer check a verdict instead of trusting it.
     *
     * <p>The field names are also the keys used inside {@code analysis_runs.trace}, because this
     * record is what is written there and read back.
     */
    public record RuleEvaluationView(
            UUID ruleId,
            String ruleName,
            RuleScope appliesTo,
            BigDecimal weight,
            boolean triggered,
            BigDecimal score,
            RuleVerdictSource source,
            int evaluatedTransactionCount,
            int matchedCount,
            List<UUID> matchedTransactionIds,
            String rationale,
            String sql) {

        /** Matched ids kept in the trace; the complete set is in {@code risk_assessments}. */
        public static final int MAX_MATCHED_IDS = 50;

        public RuleEvaluationView {
            matchedTransactionIds = matchedTransactionIds == null ? List.of()
                    : List.copyOf(matchedTransactionIds);
        }

        public static RuleEvaluationView from(RuleOutcome outcome) {
            return new RuleEvaluationView(
                    outcome.ruleId(),
                    outcome.ruleName(),
                    outcome.appliesTo(),
                    outcome.weight(),
                    outcome.triggered(),
                    outcome.score(),
                    outcome.source(),
                    outcome.evaluatedTransactionCount(),
                    outcome.matchedCount(),
                    outcome.matchedTransactionIds().stream().limit(MAX_MATCHED_IDS).toList(),
                    outcome.rationale(),
                    outcome.sql());
        }

        /**
         * Rebuilt from {@code risk_assessments} alone. Used only as a fallback when a run's trace
         * cannot be read: the scores and counts are authoritative, while the rationale and the query
         * behind the verdict are not available from the table. The source is still
         * {@code SQL_DERIVED} - those rows could not have been written any other way - so the
         * reviewer is told the truth about where the verdict came from even when the query that
         * produced it has been lost with the trace.
         */
        public static RuleEvaluationView from(RuleEvaluationRow row) {
            boolean triggered = row.getTriggeredCount() > 0;
            return new RuleEvaluationView(
                    row.getRuleId(),
                    row.getRuleName(),
                    row.getAppliesTo(),
                    row.getWeight(),
                    triggered,
                    row.getScore(),
                    RuleVerdictSource.SQL_DERIVED,
                    (int) row.getEvaluatedCount(),
                    (int) row.getTriggeredCount(),
                    List.of(),
                    null,
                    null);
        }
    }

    /**
     * Full result of one analysis run: {@code GET /api/analyses/{assessmentId}}.
     *
     * <p>Three bands are reported because they can legitimately differ, and a reviewer must be able
     * to see which is which: {@code mechanicalRiskLevel} is arithmetic over SQL-derived scores,
     * {@code agentRiskLevel} is what the model asked for, and {@code riskLevel} is what stands. The
     * last is the mechanical one unless the agent escalated above it, which it may only do with
     * {@code escalationJustification} recorded - so the UI can say "escalated from HIGH to CRITICAL
     * because ..." rather than presenting a raised band as if the totals had produced it.
     *
     * @param riskLevel      the band on record: {@code mechanicalRiskLevel}, or the agent's higher
     *                       one when it justified the escalation. Never lower than the mechanical
     *                       band
     * @param mechanicalRiskLevel {@link #totalScore()} banded, that total being the sum of the
     *                       weights of the rules whose queries returned rows
     * @param agentRiskLevel the level the agent itself proposed, kept so the two can be compared
     * @param escalationJustification why the agent raised the band; non-null exactly when
     *                       {@code riskLevel} is above {@code mechanicalRiskLevel}
     * @param coveragePercent share of the coverage set that ended with a verdict; 100 on
     *                        every {@code COMPLETED} run, because a run that leaves a rule unjudged
     *                        is persisted {@code FAILED} instead
     * @param trace          the ReAct transcript, {@code {"steps":[...]}} plus the persisted rule
     *                       detail; live steps while the run is still in flight
     */
    public record AnalysisResult(
            UUID assessmentId,
            UUID customerId,
            String customerName,
            AnalysisStatus status,
            RiskLevel riskLevel,
            RiskLevel mechanicalRiskLevel,
            RiskLevel agentRiskLevel,
            String escalationJustification,
            BigDecimal totalScore,
            String summary,
            String recommendations,
            int rulesTotal,
            int rulesEvaluated,
            boolean coverageComplete,
            double coveragePercent,
            int triggeredRuleCount,
            String model,
            int steps,
            Long durationMs,
            String requestedBy,
            Instant createdAt,
            Instant completedAt,
            String error,
            List<RuleEvaluationView> ruleEvaluations,
            JsonNode trace) {

        public AnalysisResult {
            ruleEvaluations = ruleEvaluations == null ? List.of() : List.copyOf(ruleEvaluations);
        }
    }

    /** Row of the analysis history: {@code GET /api/customers/{id}/analyses}. */
    public record AnalysisSummary(
            UUID assessmentId,
            UUID customerId,
            String customerName,
            AnalysisStatus status,
            RiskLevel riskLevel,
            BigDecimal totalScore,
            int rulesTotal,
            int rulesEvaluated,
            boolean coverageComplete,
            String requestedBy,
            Instant createdAt,
            Instant completedAt) {

        public static AnalysisSummary from(AnalysisRunSummary run) {
            return new AnalysisSummary(
                    run.getAssessmentId(),
                    run.getCustomerId(),
                    fullName(run.getCustomerFirstName(), run.getCustomerLastName()),
                    run.getStatus(),
                    run.getRiskLevel(),
                    run.getTotalScore(),
                    run.getRulesTotal(),
                    run.getRulesEvaluated(),
                    run.getCoverageComplete(),
                    run.getRequestedBy(),
                    run.getCreatedAt(),
                    run.getCompletedAt());
        }

        public static AnalysisSummary from(AnalysisRun run) {
            return new AnalysisSummary(
                    run.getAssessmentId(),
                    run.getCustomer() == null ? null : run.getCustomer().getCustomerId(),
                    run.getCustomer() == null ? null : run.getCustomer().getFullName(),
                    run.getStatus(),
                    run.getRiskLevel(),
                    run.getTotalScore(),
                    run.getRulesTotal(),
                    run.getRulesEvaluated(),
                    run.isCoverageComplete(),
                    run.getRequestedBy(),
                    run.getCreatedAt(),
                    run.getCompletedAt());
        }

        private static String fullName(String firstName, String lastName) {
            if (firstName == null && lastName == null) {
                return null;
            }
            if (firstName == null) {
                return lastName;
            }
            return lastName == null ? firstName : firstName + " " + lastName;
        }
    }
}
