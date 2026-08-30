package com.sq.caa.service;

import com.sq.caa.agent.AgentProperties;
import com.sq.caa.agent.AgentRunFailedException;
import com.sq.caa.agent.AgentRunResult;
import com.sq.caa.agent.AnalysisExecutor;
import com.sq.caa.agent.AnalysisProgressListener;
import com.sq.caa.agent.AnalysisStreamRegistry;
import com.sq.caa.agent.AnalysisTrace;
import com.sq.caa.agent.IncompleteRuleCoverageException;
import com.sq.caa.agent.ReActRiskAgent;
import com.sq.caa.agent.RiskAssessmentRows;
import com.sq.caa.agent.RiskAssessmentWriter;
import com.sq.caa.agent.UnjudgedRule;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
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
 * <p><b>Why a run can fail on coverage alone.</b> A verdict exists only where a query ran: the agent
 * reads each rule's condition, writes the SELECT that answers it, and PostgreSQL decides. Nothing
 * fills in a rule it skipped, and a rule whose query never executed is unjudged rather than
 * cleared. So
 * the guarantee is enforced at the only place that can write {@code COMPLETED} - a run reaches that
 * status only when every applicable rule has an agent verdict, and a run that ran out of turns with
 * rules unjudged is written as {@code FAILED} with those rules named in {@code error} and in the
 * trace. A partial analysis is never presented as a finished one.
 *
 * <p><b>Why a failed run still keeps its work.</b> Whatever the reason for the failure - a dropped
 * connection, a refused prompt, an unfinished checklist - every verdict the agent did submit is
 * persisted, with its rows in {@code risk_assessments} and its rationale in the trace. Nine verdicts
 * out of twelve is not a complete review, but it is not worthless either, and re-deriving it would
 * be impossible: there is no engine to re-derive it with.
 *
 * <p><b>Why progress is written while the run is still going.</b> A run is minutes of model time,
 * and a client that loses the SSE stream falls back to polling {@code GET /api/analyses/{id}}. If
 * {@code steps} and {@code rules_evaluated} only appeared at the end, that fallback would show
 * "0/12, 0 steps" for the whole run and look frozen. The agent therefore reports its counters as it
 * goes and they are written with one small UPDATE, rate-limited so the cost stays negligible next to
 * a model turn.
 *
 * <p><b>How a rule's score is written.</b> {@code risk_assessments} carries one row per
 * (transaction, rule) pair judged - "in scope" meaning every transaction whose activity type matches
 * the rule's {@code applies_to} - and a rule contributes exactly its weight, once. Those two
 * requirements are reconciled by distributing the rule's score across the transactions its query
 * returned, largest-remainder style, and writing {@code 0.00} for the in-scope transactions the
 * query did not return and for every rule whose query returned nothing. The sum of the column is then exactly the
 * run's total score, and the table alone shows which rules were judged - for every rule that had at
 * least one transaction in scope. A rule whose scope is empty (an {@code ALL}-scoped rule for a
 * customer with no activity) is judged like any other but has no transaction to key a row on; for
 * that rule the run header's {@code rules_evaluated} / {@code rules_total} /
 * {@code coverage_complete}, written below on every path, are the authoritative record.
 */
@Service
public class RiskAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RiskAnalysisService.class);

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final String EMPTY_TRACE = "{\"steps\":[]}";
    private static final int MAX_ERROR_LENGTH = 2000;

    /**
     * Shortest gap between two progress writes of the same run. A model turn takes seconds, so this
     * only ever collapses a burst; a rule verdict is written immediately whatever the gap.
     */
    private static final long MIN_PROGRESS_INTERVAL_MS = 2000L;

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
    private final EntityManager entityManager;

    public RiskAnalysisService(ReActRiskAgent agent,
            AnalysisStreamRegistry streams,
            AnalysisRunRepository analysisRuns,
            RiskAssessmentRepository riskAssessments,
            CustomerService customerService,
            RiskRuleService riskRuleService,
            JsonMapper jsonMapper,
            AgentProperties properties,
            AnalysisExecutor executor,
            PlatformTransactionManager transactionManager,
            EntityManagerFactory entityManagerFactory) {
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
        // A transaction-aware shared proxy: the rows of a run are written through the EntityManager
        // rather than through the repository, see RiskAssessmentWriter.
        this.entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
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
            AgentRunResult result = agent.run(assessmentId, customerId, trace,
                    new ProgressWriter(assessmentId, trace));
            if (!result.coverageComplete()) {
                // The loop already refuses to return an incomplete run; restating the invariant here
                // means the one method that can write COMPLETED enforces it itself, so no future
                // caller of the agent can slip a partial analysis past this point.
                throw new AgentRunFailedException(result, new IncompleteRuleCoverageException(
                        result.rulesTotal(), result.unjudgedRules(), result.unjudgedRuleNames()));
            }
            persist(result, trace, AnalysisStatus.COMPLETED, null,
                    System.currentTimeMillis() - startedAt);
            log.info("Analysis {} completed: {} ({}), all {} applicable rule(s) judged by the agent",
                    assessmentId, result.riskLevel(), result.totalScore(), result.rulesTotal());
        } catch (AgentRunFailedException e) {
            // The loop settled the run from the work the agent had done, so the partial result is
            // persisted as-is: there is nothing to re-derive it from.
            recover(assessmentId, trace, e.result(), e.getCause() == null ? e : e.getCause(),
                    System.currentTimeMillis() - startedAt, customerId);
        } catch (Exception e) {
            recover(assessmentId, trace, null, e, System.currentTimeMillis() - startedAt, customerId);
        } finally {
            streams.close(assessmentId);
        }
    }

    /**
     * Failure path: the run ends {@code FAILED}, keeping every verdict the agent obtained.
     *
     * <p>Three things reach here - a broken conversation, a checklist the agent never finished, or a
     * failure so early that there is nothing to keep. In the first two the settled result carries the
     * agent's verdicts and they are written exactly as a successful run's are, so the rows in
     * {@code risk_assessments} and the rationales in the trace survive; what differs is the status,
     * the {@code error} naming what went wrong, and {@code coverage_complete}, which is false
     * whenever a rule was left unjudged. In the third there is only the error to record.
     *
     * <p>What deliberately does not happen is any attempt to complete the coverage set without the
     * agent. There is no engine to do it with, and inventing verdicts to make a counter read 100%
     * would turn an honest failure into a false assurance.
     */
    private void recover(UUID assessmentId, AnalysisTrace trace, AgentRunResult settled,
            Throwable cause, long durationMs, UUID customerId) {
        String message = describeFailure(cause, settled);
        log.error("Analysis {} for customer {} failed: {}", assessmentId, customerId, message, cause);
        trace.error(message);
        if (settled == null) {
            log.warn("Analysis {} failed before any verdict was recorded; nothing to persist beyond "
                    + "the failure itself", assessmentId);
            markFailed(assessmentId, message, durationMs, trace.size());
            trace.publishStatus(statusPayload(assessmentId, AnalysisStatus.FAILED, null, null, 0, 0,
                    false, message));
            return;
        }
        try {
            persist(settled, trace, AnalysisStatus.FAILED, message, durationMs);
            log.info("Analysis {} failed after {} step(s); {} of {} rule(s) had been judged and were "
                            + "kept, {} left unjudged", assessmentId, settled.steps(),
                    settled.rulesJudged(), settled.rulesTotal(), settled.unjudgedRules().size());
        } catch (Exception nested) {
            log.error("Analysis {} could not even persist the work it had done", assessmentId, nested);
            trace.error("The partial result could not be persisted: " + describe(nested));
            markFailed(assessmentId, message, durationMs, trace.size());
            trace.publishStatus(statusPayload(assessmentId, AnalysisStatus.FAILED, null, null,
                    settled.rulesTotal(), settled.rulesJudged(), false, message));
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
            // Written before the run header is loaded: the writer clears the persistence context as
            // it batches, which would detach anything already loaded here.
            RiskAssessmentWriter.write(entityManager, rows);
            AnalysisRun run = analysisRuns.findById(result.assessmentId()).orElseThrow(
                    () -> new IllegalStateException("Analysis run " + result.assessmentId()
                            + " disappeared while it was executing"));
            run.setStatus(status);
            run.setRiskLevel(result.riskLevel());
            run.setTotalScore(result.totalScore());
            run.setSummary(result.summary());
            run.setRecommendations(result.recommendations());
            run.setRulesTotal(result.rulesTotal());
            // Both counters are the truth about this run and nothing else: how many of the
            // applicable rules the agent actually judged, out of how many applied. coverage_complete
            // is derived from the outcomes themselves, so it is true exactly when the two agree -
            // which, for a COMPLETED run, execute() has already required.
            run.setRulesEvaluated(result.rulesJudged());
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
                result.totalScore(), result.rulesTotal(), result.rulesJudged(),
                result.coverageComplete(), error, result.steps()));
    }

    /**
     * Publishes the counters of a run that is still going, to the database and to the stream.
     *
     * <p>Called from the analysis worker thread, so it is deliberately cheap: one indexed UPDATE of
     * two integer columns, at most once every {@value #MIN_PROGRESS_INTERVAL_MS} ms unless a rule
     * verdict just landed, and never a write per token. A failure here is logged and dropped - the
     * analysis is the product, the progress report is a view of it.
     */
    private final class ProgressWriter implements AnalysisProgressListener {

        private final UUID assessmentId;
        private final AnalysisTrace trace;
        private int lastSteps = -1;
        private int lastRulesEvaluated = -1;
        private long lastWriteAt;

        private ProgressWriter(UUID assessmentId, AnalysisTrace trace) {
            this.assessmentId = assessmentId;
            this.trace = trace;
        }

        @Override
        public synchronized void onProgress(int steps, int rulesEvaluated, int rulesTotal) {
            boolean coverageMoved = rulesEvaluated != lastRulesEvaluated;
            if (!coverageMoved && steps == lastSteps) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!coverageMoved && now - lastWriteAt < MIN_PROGRESS_INTERVAL_MS) {
                return;
            }
            lastSteps = steps;
            lastRulesEvaluated = rulesEvaluated;
            lastWriteAt = now;
            try {
                transactions.executeWithoutResult(tx -> entityManager.createQuery("""
                        update AnalysisRun run
                        set run.steps = :steps, run.rulesEvaluated = :evaluated
                        where run.assessmentId = :assessmentId and run.status = :status
                        """)
                        .setParameter("steps", steps)
                        .setParameter("evaluated", rulesEvaluated)
                        .setParameter("assessmentId", assessmentId)
                        .setParameter("status", AnalysisStatus.RUNNING)
                        .executeUpdate());
            } catch (RuntimeException e) {
                log.debug("Could not record the progress of analysis {}", assessmentId, e);
            }
            trace.publishStatus(statusPayload(assessmentId, AnalysisStatus.RUNNING, null, null,
                    rulesTotal, rulesEvaluated, false, null, steps));
        }
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
     * {@code {"steps":[...]}} shape, plus the per-rule detail, the coverage counters and the names of
     * any rules left unjudged - everything the analysis page needs but {@code risk_assessments}
     * cannot carry, its columns being fixed by the assignment.
     */
    private ObjectNode traceDocument(AnalysisTrace trace, AgentRunResult result,
            List<RuleEvaluationView> evaluations) {
        ObjectNode document = trace.toJson();
        document.set("ruleEvaluations", jsonMapper.valueToTree(evaluations));
        if (result.agentRiskLevel() != null) {
            document.put("agentRiskLevel", result.agentRiskLevel().name());
        }
        // The escalation pair. analysis_runs.risk_level holds the band that stands and its columns
        // are fixed by the assignment, so the band the rule scores alone produced - and the reason
        // the agent was allowed to raise it - are kept here. Without both, a raised band would be
        // indistinguishable from one the arithmetic produced.
        if (result.mechanicalRiskLevel() != null) {
            document.put("mechanicalRiskLevel", result.mechanicalRiskLevel().name());
        }
        if (result.escalationJustification() != null) {
            document.put("escalationJustification", result.escalationJustification());
        }
        ObjectNode coverage = document.putObject("coverage");
        coverage.put("rulesTotal", result.rulesTotal());
        coverage.put("rulesEvaluated", evaluations.size());
        coverage.put("complete", result.coverageComplete());
        // Named, not merely counted: a reviewer opening a failed run has to be able to see which
        // rules were never judged without reading the transcript.
        ArrayNode unjudged = coverage.putArray("unjudgedRules");
        for (UnjudgedRule rule : result.unjudgedRules()) {
            ObjectNode entry = unjudged.addObject();
            entry.put("ruleId", rule.ruleId().toString());
            entry.put("ruleName", rule.ruleName());
        }
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

        int triggered = (int) evaluations.stream().filter(RuleEvaluationView::triggered).count();
        int rulesTotal = Math.max(run.getRulesTotal(), evaluations.size());

        return new AnalysisResult(
                run.getAssessmentId(),
                run.getCustomer().getCustomerId(),
                run.getCustomer().getFullName(),
                run.getStatus(),
                run.getRiskLevel(),
                mechanicalRiskLevel(stored, run),
                riskLevel(stored, "agentRiskLevel"),
                text(stored, "escalationJustification"),
                run.getTotalScore(),
                run.getSummary(),
                run.getRecommendations(),
                rulesTotal,
                run.getRulesEvaluated(),
                run.isCoverageComplete(),
                coveragePercent(run.getRulesEvaluated(), rulesTotal),
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

    /**
     * The band the rule scores alone produced.
     *
     * <p>Read from the trace of runs written since verdicts became SQL-derived, and re-derived from
     * the stored total for older ones - the two agree by construction, because the recorded band is
     * only ever the banded total or an escalation above it.
     */
    private static RiskLevel mechanicalRiskLevel(JsonNode trace, AnalysisRun run) {
        RiskLevel stored = riskLevel(trace, "mechanicalRiskLevel");
        if (stored != null) {
            return stored;
        }
        return run.getTotalScore() == null ? run.getRiskLevel() : RiskLevel.forScore(run.getTotalScore());
    }

    private static RiskLevel riskLevel(JsonNode trace, String field) {
        String value = text(trace, field);
        if (value == null) {
            return null;
        }
        try {
            return RiskLevel.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode trace, String field) {
        JsonNode node = trace == null ? null : trace.get(field);
        return node == null || !node.isString() ? null : node.stringValue();
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
                run.isCoverageComplete(), run.getError(), run.getSteps());
    }

    private String statusPayload(UUID assessmentId, AnalysisStatus status,
            RiskLevel riskLevel, BigDecimal totalScore, int rulesTotal,
            int rulesEvaluated, boolean coverageComplete, String error) {
        return statusPayload(assessmentId, status, riskLevel, totalScore, rulesTotal, rulesEvaluated,
                coverageComplete, error, 0);
    }

    private String statusPayload(UUID assessmentId, AnalysisStatus status,
            RiskLevel riskLevel, BigDecimal totalScore, int rulesTotal,
            int rulesEvaluated, boolean coverageComplete, String error, int steps) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("assessmentId", assessmentId.toString());
        node.put("status", status.name());
        node.put("riskLevel", riskLevel == null ? null : riskLevel.name());
        node.put("totalScore", totalScore == null ? null : totalScore.toPlainString());
        node.put("rulesTotal", rulesTotal);
        node.put("rulesEvaluated", rulesEvaluated);
        node.put("coverageComplete", coverageComplete);
        node.put("coveragePercent", coveragePercent(rulesEvaluated, rulesTotal));
        node.put("steps", steps);
        node.put("error", error);
        return node.toString();
    }

    private static double coveragePercent(int evaluated, int total) {
        if (total <= 0) {
            return 100.0;
        }
        return Math.round(10000.0 * evaluated / total) / 100.0;
    }

    /**
     * The failure as it is written to {@code analysis_runs.error}.
     *
     * <p>When the run also left rules unjudged and the cause does not already say so - a dropped
     * connection that happened to strike mid-checklist - the unjudged rules are named here too, so
     * the {@code error} column alone tells a reviewer both what broke and what was missed.
     */
    private static String describeFailure(Throwable cause, AgentRunResult settled) {
        String message = describe(cause);
        if (settled == null || settled.coverageComplete()
                || cause instanceof IncompleteRuleCoverageException) {
            return message;
        }
        return truncate(message + " The analysis is also incomplete: " + settled.unjudgedRules().size()
                + " of " + settled.rulesTotal() + " applicable rule(s) never received a verdict: "
                + settled.unjudgedRuleNames() + ".");
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
