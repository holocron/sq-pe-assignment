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
     * coverage visible. {@code source} says whether the agent produced the verdict or the
     * deterministic backfill did; {@code disagreement} marks the rules where the agent and the
     * engine differed and the engine won.
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
            boolean degraded,
            List<String> degradationNotes,
            String explanation,
            String rationale,
            Boolean agentTriggered,
            BigDecimal agentScore,
            boolean disagreement) {

        /** Matched ids kept in the trace; the complete set is in {@code risk_assessments}. */
        public static final int MAX_MATCHED_IDS = 50;

        public RuleEvaluationView {
            matchedTransactionIds = matchedTransactionIds == null ? List.of()
                    : List.copyOf(matchedTransactionIds);
            degradationNotes = degradationNotes == null ? List.of() : List.copyOf(degradationNotes);
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
                    outcome.degraded(),
                    outcome.degradationNotes(),
                    outcome.explanation(),
                    outcome.rationale(),
                    outcome.agentTriggered(),
                    outcome.agentScore(),
                    outcome.disagreement());
        }

        /**
         * Rebuilt from {@code risk_assessments} alone. Used only as a fallback when a run's trace
         * cannot be read: the scores and counts are authoritative, the narrative fields are not
         * available from the table.
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
                    RuleVerdictSource.DETERMINISTIC_FALLBACK,
                    (int) row.getEvaluatedCount(),
                    (int) row.getTriggeredCount(),
                    List.of(),
                    false,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    false);
        }
    }

    /**
     * Full result of one analysis run: {@code GET /api/analyses/{assessmentId}}.
     *
     * @param riskLevel      banded from {@link #totalScore()}, which is the sum of the deterministic
     *                       rule scores
     * @param agentRiskLevel the level the agent itself proposed, kept so the two can be compared
     * @param coveragePercent share of the coverage set that ended with a verdict; 100 on any run
     *                        that completed, because of the deterministic backfill
     * @param trace          the ReAct transcript, {@code {"steps":[...]}} plus the persisted rule
     *                       detail; live steps while the run is still in flight
     */
    public record AnalysisResult(
            UUID assessmentId,
            UUID customerId,
            String customerName,
            AnalysisStatus status,
            RiskLevel riskLevel,
            RiskLevel agentRiskLevel,
            BigDecimal totalScore,
            String summary,
            String recommendations,
            int rulesTotal,
            int rulesEvaluated,
            boolean coverageComplete,
            double coveragePercent,
            int rulesEvaluatedByAgent,
            int rulesBackfilled,
            int disagreementCount,
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
