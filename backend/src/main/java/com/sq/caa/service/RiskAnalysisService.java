package com.sq.caa.service;

import com.sq.caa.agent.AgentProperties;
import com.sq.caa.agent.AgentRunFailedException;
import com.sq.caa.agent.AgentRunResult;
import com.sq.caa.agent.AnalysisExecutor;
import com.sq.caa.agent.AnalysisStreamRegistry;
import com.sq.caa.agent.AnalysisTrace;
import com.sq.caa.agent.ReActRiskAgent;
import com.sq.caa.agent.RiskAssessmentRows;
import com.sq.caa.agent.RuleVerdictSource;
import com.sq.caa.domain.AnalysisRun;
import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskAssessment;
import com.sq.caa.domain.RiskLevel;
import com.sq.caa.repository.AnalysisRunRepository;
import com.sq.caa.repository.RiskAssessmentRepository;
import com.sq.caa.web.dto.AnalysisDtos.AnalysisAccepted;
import com.sq.caa.web.dto.AnalysisDtos.AnalysisResult;
import com.sq.caa.web.dto.AnalysisDtos.AnalysisSummary;
import com.sq.caa.web.dto.AnalysisDtos.RuleEvaluationView;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lifecycle of an AI analysis: accept it, run it off the request thread, persist it, serve it.
 *
 * <p><b>Why asynchronous.</b> One run is minutes of model time. {@code POST .../analyses} therefore
 * writes a {@code RUNNING} row, hands the work to a bounded executor and answers {@code 202} with
 * the {@code assessment_id} the client then follows over SSE. A run can only ever leave
 * {@code RUNNING} in one of two ways - completed or failed with the reason recorded - because the
 * worker's {@code finally} block always writes an end state.
 *
 * <p><b>Why a failed run still scores.</b> If the agent loop throws, the deterministic rule engine
 * is still run over the whole coverage set before the run is marked {@code FAILED}. The narrative is
 * lost but the rule coverage is not, so "every applicable rule was evaluated" holds on every run,
 * not only on the happy path.
 *
 * <p><b>How a rule's score is written.</b> {@code risk_assessments} carries one row per
 * (transaction, rule) pair evaluated, and a rule's score is capped at its weight. Those two
 * requirements are reconciled by distributing the rule's weight across the transactions that
 * actually matched it, largest-remainder style, and writing {@code 0.00} for the in-scope
 * transactions that did not match and for every rule that did not trigger. The sum of the column is
 * then exactly the run's total score, and the table alone proves which rules were evaluated.
 */
@Service
public class RiskAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RiskAnalysisService.class);

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final String EMPTY_TRACE = "{\"steps\":[]}";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final ReActRiskAgent agent;
    private final AnalysisStreamRegistry streams;
    private final AnalysisRunRepository analysisRuns;
    private final RiskAssessmentRepository riskAssessments;
    private final CustomerService customerService;
    private final RiskRuleService riskRuleService;
    private final JsonMapper jsonMapper;
    private final AgentProperties properties;
    private final AnalysisExecutor executor;
    private final TransactionTemplate transactions;

    public RiskAnalysisService(ReActRiskAgent agent,
            AnalysisStreamRegistry streams,
            AnalysisRunRepository analysisRuns,
            RiskAssessmentRepository riskAssessments,
            CustomerService customerService,
            RiskRuleService riskRuleService,
            JsonMapper jsonMapper,
            AgentProperties properties,
            AnalysisExecutor executor,
            PlatformTransactionManager transactionManager) {
        this.agent = agent;
        this.streams = streams;
        this.analysisRuns = analysisRuns;
        this.riskAssessments = riskAssessments;
        this.customerService = customerService;
        this.riskRuleService = riskRuleService;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.executor = executor;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Nothing survives a restart: the executor and the live transcripts are in memory, so a run left
     * {@code RUNNING} by a crash or a redeploy can never make progress again. Marking those runs
     * failed at startup is what keeps the "a run is never stuck in RUNNING" promise true across
     * process boundaries, not only across exceptions.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failRunsOrphanedByARestart() {
        List<AnalysisRun> orphans = analysisRuns.findByStatusOrderByCreatedAtAsc(AnalysisStatus.RUNNING);
        if (orphans.isEmpty()) {
            return;
        }
        log.warn("Marking {} analysis run(s) left RUNNING by a previous process as failed",
                orphans.size());
        for (AnalysisRun orphan : orphans) {
            markFailed(orphan.getAssessmentId(),
                    "The application restarted while this analysis was running.",
                    orphan.getDurationMs() == null ? 0L : orphan.getDurationMs(), orphan.getSteps());
        }
    }

    // ==================================================================
    // Starting a run
    // ==================================================================

    /**
     * Accepts an analysis request: persists the {@code RUNNING} header, queues the work and returns
     * immediately.
     *
     * @throws ResponseStatusException {@code 404} for an unknown customer, {@code 503} when the
     *                                 analysis queue is saturated
     */
    public AnalysisAccepted start(UUID customerId, String requestedBy) {
        Customer customer = customerService.requireCustomer(customerId);
        int rulesTotal = riskRuleService.coverageSetFor(customerId).size();
        UUID assessmentId = UUID.randomUUID();
        AnalysisTrace trace = streams.open(assessmentId);

        AnalysisRun run = AnalysisRun.builder()
                .assessmentId(assessmentId)
                .customer(customer)
                .status(AnalysisStatus.RUNNING)
                .rulesTotal(rulesTotal)
                .rulesEvaluated(0)
                .coverageComplete(false)
                .model(agent.modelId())
                .steps(0)
                .trace(EMPTY_TRACE)
                .requestedBy(requestedBy)
                .createdAt(Instant.now())
                .build();
        try {
            analysisRuns.save(run);
        } catch (RuntimeException e) {
            streams.close(assessmentId);
            throw e;
        }
        trace.publishStatus(statusPayload(assessmentId, AnalysisStatus.RUNNING, null, null, rulesTotal,
                0, false, null));

        try {
            executor.submit(() -> execute(assessmentId, customerId, trace));
        } catch (RejectedExecutionException e) {
            // ThreadPoolTaskExecutor wraps the pool's rejection in a TaskRejectedException, which is
            // itself a RejectedExecutionException, so one catch covers both.
            log.warn("Analysis queue is full; rejecting analysis {} for customer {}", assessmentId,
                    customerId);
            trace.error("The analysis queue is full; this run was not started.");
            markFailed(assessmentId, "The analysis queue is full. Try again once a running analysis "
                    + "has finished.", 0L, 0);
            streams.close(assessmentId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Too many analyses are already running. Try again in a few minutes.");
        }
        log.info("Queued analysis {} for customer {} requested by {}", assessmentId, customerId,
                requestedBy);
        return new AnalysisAccepted(assessmentId, AnalysisStatus.RUNNING);
    }

    /** The worker body. Always leaves the run in a terminal state and always closes the stream. */
    private void execute(UUID assessmentId, UUID customerId, AnalysisTrace trace) {
        long startedAt = System.currentTimeMillis();
        try {
            AgentRunResult result = agent.run(assessmentId, customerId, trace);
            persist(result, trace, AnalysisStatus.COMPLETED, null,
                    System.currentTimeMillis() - startedAt);
            log.info("Analysis {} completed: {} ({}), {}/{} rules evaluated by the agent, coverage {}",
                    assessmentId, result.riskLevel(), result.totalScore(),
                    result.rulesEvaluatedByAgent(), result.rulesTotal(),
                    result.coverageComplete() ? "complete" : "completed by fallback");
        } catch (AgentRunFailedException e) {
            // The loop already settled the coverage set from the work the agent had done, so the
            // partial run is persisted as-is rather than re-derived from scratch.
            recover(assessmentId, trace, e.result(), e.getCause() == null ? e : e.getCause(),
                    System.currentTimeMillis() - startedAt, customerId);
        } catch (Exception e) {
            recover(assessmentId, trace, null, e, System.currentTimeMillis() - startedAt, customerId);
        } finally {
            streams.close(assessmentId);
        }
    }

    /**
     * Failure path. The narrative may be incomplete, but the rule coverage never is.
     *
     * <p>When the agent loop got far enough to settle its own coverage set it hands the partial run
     * over ({@code settled}); everything the agent actually established is kept and only the rules
     * it never reached are marked as deterministic fallbacks. When it failed before that - loading
     * the customer, say - the whole coverage set is evaluated by the engine alone. Either way the
     * run ends with a verdict and a score for every applicable rule, marked {@code FAILED} with the
     * reason.
     */
    private void recover(UUID assessmentId, AnalysisTrace trace, AgentRunResult settled,
            Throwable cause, long durationMs, UUID customerId) {
        String message = describe(cause);
        log.error("Analysis {} for customer {} failed: {}", assessmentId, customerId, message, cause);
        trace.error(message);
        try {
            AgentRunResult fallback = settled != null
                    ? settled
                    : agent.deterministicOnly(assessmentId, customerId, trace);
            persist(fallback, trace, AnalysisStatus.FAILED, message, durationMs);
            log.info("Analysis {} failed after {} step(s) but all {} rule(s) still ended with a verdict "
                            + "({} from the agent)", assessmentId, fallback.steps(),
                    fallback.ruleOutcomes().size(), fallback.rulesEvaluatedByAgent());
        } catch (Exception nested) {
            log.error("Analysis {} could not even be completed deterministically", assessmentId, nested);
            trace.error("The deterministic fallback also failed: " + describe(nested));
            markFailed(assessmentId, message, durationMs, trace.size());
            trace.publishStatus(statusPayload(assessmentId, AnalysisStatus.FAILED, null, null, 0, 0,
                    false, message));
        }
    }

    // ==================================================================
    // Persistence
    // ==================================================================

    /** Writes the per-(transaction, rule) rows and the run header in one transaction. */
    private void persist(AgentRunResult result, AnalysisTrace trace, AnalysisStatus status, String error,
            long durationMs) {
        List<RuleEvaluationView> evaluations = result.ruleOutcomes().stream()
                .map(RuleEvaluationView::from)
                .toList();
        List<RiskAssessment> rows = RiskAssessmentRows.build(result.assessmentId(),
                result.ruleOutcomes(), Instant.now());
        String traceJson = traceDocument(trace, result, evaluations).toString();

        transactions.executeWithoutResult(tx -> {
            riskAssessments.deleteByAssessmentId(result.assessmentId());
            if (!rows.isEmpty()) {
                riskAssessments.saveAll(rows);
            }
            AnalysisRun run = analysisRuns.findById(result.assessmentId()).orElseThrow(
                    () -> new IllegalStateException("Analysis run " + result.assessmentId()
                            + " disappeared while it was executing"));
            run.setStatus(status);
            run.setRiskLevel(result.riskLevel());
            run.setTotalScore(result.totalScore());
            run.setSummary(result.summary());
            run.setRecommendations(result.recommendations());
            run.setRulesTotal(result.rulesTotal());
            // Every rule of the coverage set ends with a verdict, whether the agent produced it or
            // the deterministic backfill did: coverage is 100% on any run that reaches this point.
            run.setRulesEvaluated(result.ruleOutcomes().size());
            run.setCoverageComplete(result.coverageComplete());
            run.setModel(result.model());
            run.setSteps(result.steps());
            run.setDurationMs(durationMs);
            run.setTrace(traceJson);
            run.setError(error);
            run.setCompletedAt(Instant.now());
            analysisRuns.save(run);
        });

        trace.publishStatus(statusPayload(result.assessmentId(), status, result.riskLevel(),
                result.totalScore(), result.rulesTotal(), result.ruleOutcomes().size(),
                result.coverageComplete(), error));
    }

    /** Last-resort end state: the run is marked failed even if nothing else could be written. */
    private void markFailed(UUID assessmentId, String error, long durationMs, int steps) {
        try {
            transactions.executeWithoutResult(tx -> analysisRuns.findById(assessmentId).ifPresent(run -> {
                run.setStatus(AnalysisStatus.FAILED);
                run.setError(truncate(error));
                run.setDurationMs(durationMs);
                run.setSteps(steps);
                run.setCompletedAt(Instant.now());
                analysisRuns.save(run);
            }));
        } catch (RuntimeException e) {
            log.error("Could not mark analysis {} as failed", assessmentId, e);
        }
    }

    /**
     * The {@code analysis_runs.trace} document: the transcript in the published
     * {@code {"steps":[...]}} shape, plus the per-rule detail and the coverage counters the analysis
     * page needs but {@code risk_assessments} cannot carry (its columns are fixed by the assignment).
     */
    private ObjectNode traceDocument(AnalysisTrace trace, AgentRunResult result,
            List<RuleEvaluationView> evaluations) {
        ObjectNode document = trace.toJson();
        document.set("ruleEvaluations", jsonMapper.valueToTree(evaluations));
        if (result.agentRiskLevel() != null) {
            document.put("agentRiskLevel", result.agentRiskLevel().name());
        }
        ObjectNode coverage = document.putObject("coverage");
        coverage.put("rulesTotal", result.rulesTotal());
        coverage.put("rulesEvaluated", evaluations.size());
        coverage.put("evaluatedByAgent", result.rulesEvaluatedByAgent());
        coverage.put("backfilled", result.rulesBackfilled());
        coverage.put("disagreements", result.disagreementCount());
        coverage.put("complete", result.coverageComplete());
        return document;
    }

    // ==================================================================
    // Reading
    // ==================================================================

    /** One analysis with its per-rule coverage table and its ReAct transcript. */
    @Transactional(readOnly = true)
    public AnalysisResult get(UUID assessmentId) {
        AnalysisRun run = requireRun(assessmentId);
        JsonNode stored = readTree(run.getTrace());
        List<RuleEvaluationView> evaluations = readEvaluations(stored);
        if (evaluations.isEmpty() && run.getStatus() != AnalysisStatus.RUNNING) {
            // A run whose trace could not be read is still fully auditable from the rule table.
            evaluations = riskAssessments.summariseRulesForAssessment(assessmentId).stream()
                    .map(RuleEvaluationView::from)
                    .toList();
        }
        JsonNode trace = streams.find(assessmentId)
                .<JsonNode>map(AnalysisTrace::toJson)
                .orElse(stored);

        int byAgent = (int) evaluations.stream()
                .filter(view -> view.source() == RuleVerdictSource.AGENT)
                .count();
        int disagreements = (int) evaluations.stream().filter(RuleEvaluationView::disagreement).count();
        int triggered = (int) evaluations.stream().filter(RuleEvaluationView::triggered).count();
        int rulesTotal = Math.max(run.getRulesTotal(), evaluations.size());

        return new AnalysisResult(
                run.getAssessmentId(),
                run.getCustomer().getCustomerId(),
                run.getCustomer().getFullName(),
                run.getStatus(),
                run.getRiskLevel(),
                agentRiskLevel(stored),
                run.getTotalScore(),
                run.getSummary(),
                run.getRecommendations(),
                rulesTotal,
                run.getRulesEvaluated(),
                run.isCoverageComplete(),
                coveragePercent(run.getRulesEvaluated(), rulesTotal),
                byAgent,
                evaluations.size() - byAgent,
                disagreements,
                triggered,
                run.getModel(),
                run.getSteps(),
                run.getDurationMs(),
                run.getRequestedBy(),
                run.getCreatedAt(),
                run.getCompletedAt(),
                run.getError(),
                evaluations,
                trace);
    }

    /** Analysis history of one customer, newest first. */
    @Transactional(readOnly = true)
    public List<AnalysisSummary> history(UUID customerId) {
        customerService.requireCustomer(customerId);
        return analysisRuns.findSummaries(customerId).stream().map(AnalysisSummary::from).toList();
    }

    // ==================================================================
    // Streaming
    // ==================================================================

    /**
     * Live view of a run. A run still in flight is attached to its in-memory transcript, which
     * replays what has already happened and then streams every new step. A finished run is replayed
     * from {@code analysis_runs.trace} and the stream is closed immediately, so the client never
     * hangs waiting for events that will not come.
     */
    @Transactional(readOnly = true)
    public SseEmitter stream(UUID assessmentId) {
        AnalysisRun run = requireRun(assessmentId);
        SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());
        String status = statusPayload(run);

        Optional<AnalysisTrace> live = streams.find(assessmentId);
        if (live.isPresent() && live.get().subscribe(emitter, status)) {
            return emitter;
        }
        replay(emitter, run, status);
        emitter.complete();
        return emitter;
    }

    private void replay(SseEmitter emitter, AnalysisRun run, String status) {
        JsonNode stored = readTree(run.getTrace());
        JsonNode steps = stored.get("steps");
        try {
            emitter.send(SseEmitter.event().name(AnalysisTrace.EVENT_STATUS).data(status));
            if (steps != null && steps.isArray()) {
                for (JsonNode step : steps) {
                    emitter.send(SseEmitter.event().name(AnalysisTrace.EVENT_STEP).data(step.toString()));
                }
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE client of analysis {} went away during replay", run.getAssessmentId());
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private AnalysisRun requireRun(UUID assessmentId) {
        if (assessmentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An assessment id is required.");
        }
        return analysisRuns.findByIdWithCustomer(assessmentId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Analysis " + assessmentId + " was not found."));
    }

    private List<RuleEvaluationView> readEvaluations(JsonNode trace) {
        JsonNode node = trace == null ? null : trace.get("ruleEvaluations");
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        try {
            return List.of(jsonMapper.treeToValue(node, RuleEvaluationView[].class));
        } catch (RuntimeException e) {
            log.warn("Could not read the persisted rule evaluations of an analysis run", e);
            return List.of();
        }
    }

    private static RiskLevel agentRiskLevel(JsonNode trace) {
        JsonNode node = trace == null ? null : trace.get("agentRiskLevel");
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return RiskLevel.valueOf(node.stringValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return JsonNodeFactory.instance.objectNode().set("steps",
                    JsonNodeFactory.instance.arrayNode());
        }
        try {
            return jsonMapper.readTree(json);
        } catch (RuntimeException e) {
            ObjectNode fallback = JsonNodeFactory.instance.objectNode();
            ArrayNode steps = fallback.putArray("steps");
            steps.addObject().put("n", 1).put("type", "error")
                    .put("text", "The stored trace could not be parsed.");
            return fallback;
        }
    }

    private String statusPayload(AnalysisRun run) {
        return statusPayload(run.getAssessmentId(), run.getStatus(), run.getRiskLevel(),
                run.getTotalScore(), run.getRulesTotal(), run.getRulesEvaluated(),
                run.isCoverageComplete(), run.getError());
    }

    private String statusPayload(UUID assessmentId, AnalysisStatus status,
            RiskLevel riskLevel, BigDecimal totalScore, int rulesTotal,
            int rulesEvaluated, boolean coverageComplete, String error) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("assessmentId", assessmentId.toString());
        node.put("status", status.name());
        node.put("riskLevel", riskLevel == null ? null : riskLevel.name());
        node.put("totalScore", totalScore == null ? null : totalScore.toPlainString());
        node.put("rulesTotal", rulesTotal);
        node.put("rulesEvaluated", rulesEvaluated);
        node.put("coverageComplete", coverageComplete);
        node.put("coveragePercent", coveragePercent(rulesEvaluated, rulesTotal));
        node.put("error", error);
        return node.toString();
    }

    private static double coveragePercent(int evaluated, int total) {
        if (total <= 0) {
            return 100.0;
        }
        return Math.round(10000.0 * evaluated / total) / 100.0;
    }

    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        String text = message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
        return truncate(text);
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_ERROR_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_ERROR_LENGTH) + "...";
    }
}
