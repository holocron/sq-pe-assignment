package com.sq.caa.agent;

import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.rules.EvaluationBatch;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mutable state of one analysis run, shared between the ReAct loop and the tools it exposes.
 *
 * <p>This is where rule coverage is tracked. The {@code coverageSet} is fixed before the first model
 * turn; {@code verdicts} only ever grows through {@code evaluate_rule}, and only when that rule's
 * SQL actually executed; and {@link #missingRules()} is the difference the loop refuses to finish
 * with. Nothing here can widen or shrink the coverage set once the run has started, so the gate
 * cannot be talked out of by the model - not by a rule whose prose asks to be skipped, and not by a
 * conclusion that arrives early.
 *
 * <p>{@code sqlAttempts} is the other half of that bookkeeping. A query that is rejected or errors
 * records nothing, so the rule stays outstanding and the model may fix its SQL and try again; the
 * counter bounds how often, and a rule that exhausts it is left <em>unjudged</em> rather than
 * quietly written down as "not triggered".
 *
 * <p>The scope of a rule is resolved here too, once per rule, from the run's single
 * {@link EvaluationBatch}: which of the customer's transactions a rule applies to is a fact about
 * the activity type, not a judgement, and it is what {@code evaluate_rule} checks the ids a query
 * returned against before a verdict is accepted.
 *
 * <p>All mutable collections are concurrent because tool execution order is decided by the model and
 * the framework, not by this class.
 */
public final class AgentRunContext {

    private static final Logger log = LoggerFactory.getLogger(AgentRunContext.class);

    private final UUID assessmentId;
    private final Customer customer;
    private final EvaluationBatch batch;
    private final Map<UUID, RiskRule> coverageSet;
    /** Coverage-set order, fixed at construction so every listing and every reprompt agrees. */
    private final List<UUID> orderedRuleIds;
    private final AnalysisTrace trace;
    private final AnalysisProgressListener progress;

    private final Map<UUID, Scope> scopes = new ConcurrentHashMap<>();
    private final Map<UUID, AgentRuleVerdict> verdicts = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> sqlAttempts = new ConcurrentHashMap<>();
    private final Queue<ToolTiming> timings = new ConcurrentLinkedQueue<>();
    private final AtomicReference<FinalAssessment> finalAssessment = new AtomicReference<>();
    private final AtomicBoolean finalRejected = new AtomicBoolean();
    private final AtomicBoolean compactionAnnounced = new AtomicBoolean();
    private final AtomicInteger stepsTaken = new AtomicInteger();

    public AgentRunContext(UUID assessmentId, Customer customer, EvaluationBatch batch,
            Collection<RiskRule> coverageSet, AnalysisTrace trace) {
        this(assessmentId, customer, batch, coverageSet, trace, AnalysisProgressListener.NONE);
    }

    public AgentRunContext(UUID assessmentId, Customer customer, EvaluationBatch batch,
            Collection<RiskRule> coverageSet, AnalysisTrace trace,
            AnalysisProgressListener progress) {
        this.assessmentId = assessmentId;
        this.customer = customer;
        this.batch = batch;
        this.trace = trace;
        this.progress = progress == null ? AnalysisProgressListener.NONE : progress;
        Map<UUID, RiskRule> rules = new LinkedHashMap<>();
        for (RiskRule rule : coverageSet) {
            rules.put(rule.getRuleId(), rule);
        }
        this.coverageSet = Map.copyOf(rules);
        this.orderedRuleIds = List.copyOf(rules.keySet());
    }

    public UUID assessmentId() {
        return assessmentId;
    }

    public Customer customer() {
        return customer;
    }

    public EvaluationBatch batch() {
        return batch;
    }

    public AnalysisTrace trace() {
        return trace;
    }

    /** Every rule that must receive a verdict in this run. */
    public List<RiskRule> rules() {
        List<RiskRule> rules = new ArrayList<>(orderedRuleIds.size());
        for (UUID ruleId : orderedRuleIds) {
            rules.add(coverageSet.get(ruleId));
        }
        return rules;
    }

    public int ruleCount() {
        return orderedRuleIds.size();
    }

    public RiskRule rule(UUID ruleId) {
        return ruleId == null ? null : coverageSet.get(ruleId);
    }

    // ------------------------------------------------------------------
    // Rule scope
    // ------------------------------------------------------------------

    /**
     * The transactions one rule applies to: those whose activity type matches its {@code applies_to}
     * scope, or all of them for an {@code ALL}-scoped rule. Resolved from the run's snapshot and
     * memoised, because the answer cannot change during a run.
     */
    public List<UUID> inScopeTransactionIds(UUID ruleId) {
        Scope scope = scope(ruleId);
        return scope == null ? List.of() : scope.ids();
    }

    /** How many of the customer's transactions the rule applies to. */
    public int inScopeCount(UUID ruleId) {
        return inScopeTransactionIds(ruleId).size();
    }

    /**
     * Whether one transaction is in a rule's scope. This is the check that stops a hallucinated or
     * out-of-scope transaction id from being recorded as evidence for a rule.
     */
    public boolean isInScope(UUID ruleId, UUID transactionId) {
        Scope scope = scope(ruleId);
        return scope != null && transactionId != null && scope.set().contains(transactionId);
    }

    private Scope scope(UUID ruleId) {
        RiskRule rule = rule(ruleId);
        if (rule == null) {
            return null;
        }
        return scopes.computeIfAbsent(ruleId, key -> {
            List<UUID> ids = batch.transactionIdsFor(rule.getAppliesTo());
            return new Scope(List.copyOf(ids), Set.copyOf(new LinkedHashSet<>(ids)));
        });
    }

    // ------------------------------------------------------------------
    // Coverage tracking
    // ------------------------------------------------------------------

    /** Rules the agent has submitted a verdict for. */
    public boolean isEvaluated(UUID ruleId) {
        return verdicts.containsKey(ruleId);
    }

    public int evaluatedCount() {
        return verdicts.size();
    }

    /** Rules with no agent verdict yet, in coverage-set order. This is what the gate tests. */
    public List<RiskRule> missingRules() {
        List<RiskRule> missing = new ArrayList<>();
        for (UUID ruleId : orderedRuleIds) {
            if (!verdicts.containsKey(ruleId)) {
                missing.add(coverageSet.get(ruleId));
            }
        }
        return List.copyOf(missing);
    }

    public boolean coverageComplete() {
        return verdicts.keySet().containsAll(orderedRuleIds);
    }

    public void recordVerdict(AgentRuleVerdict verdict) {
        verdicts.put(verdict.ruleId(), verdict);
        sqlAttempts.remove(verdict.ruleId());
        publishProgress();
    }

    public AgentRuleVerdict verdict(UUID ruleId) {
        return verdicts.get(ruleId);
    }

    /**
     * The run's score so far: the sum of the mechanical per-rule scores.
     *
     * <p>Arithmetic over weights, never over anything the model proposed - a rule contributes its
     * weight when its query matched and nothing when it did not.
     */
    public BigDecimal totalScore() {
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (AgentRuleVerdict verdict : verdicts.values()) {
            if (verdict.triggered() && verdict.score() != null) {
                total = total.add(verdict.score());
            }
        }
        return total;
    }

    /** The band the rule scores alone produce; the floor the agent may escalate above but not below. */
    public RiskLevel mechanicalRiskLevel() {
        return RiskLevel.forScore(totalScore());
    }

    // ------------------------------------------------------------------
    // Query attempts
    // ------------------------------------------------------------------

    /**
     * Counts one attempt to answer a rule with SQL.
     *
     * @return how many attempts that rule has now used, including this one
     */
    public int recordSqlAttempt(UUID ruleId) {
        return sqlAttempts.computeIfAbsent(ruleId, key -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * Attempts spent on one rule's query since the last one that ran.
     *
     * <p>The counter bounds <em>failures</em>, which is why {@link #recordVerdict} clears it: a query
     * that executed cost the rule nothing, and a model that legitimately re-runs a rule with a better
     * query must not find its budget already gone.
     */
    public int sqlAttempts(UUID ruleId) {
        AtomicInteger attempts = sqlAttempts.get(ruleId);
        return attempts == null ? 0 : attempts.get();
    }

    // ------------------------------------------------------------------
    // Conclusion
    // ------------------------------------------------------------------

    public FinalAssessment finalAssessment() {
        return finalAssessment.get();
    }

    public boolean isConcluded() {
        return finalAssessment.get() != null;
    }

    public void conclude(FinalAssessment assessment) {
        finalAssessment.set(assessment);
    }

    /** Records that {@code submit_final_assessment} was refused because rules were still open. */
    public void rejectConclusion() {
        finalRejected.set(true);
    }

    /** Reads and clears the rejection flag; the loop turns it into a reprompt. */
    public boolean consumeConclusionRejected() {
        return finalRejected.getAndSet(false);
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    /** Counts one model turn. Kept on the context so a failed run can still report how far it got. */
    public void recordStep() {
        stepsTaken.incrementAndGet();
        publishProgress();
    }

    /**
     * Reports the current counters to the progress listener. A listener that fails must not take the
     * analysis down with it - progress reporting is a view of the run, never part of it.
     */
    public void publishProgress() {
        try {
            progress.onProgress(stepsTaken.get(), verdicts.size(), orderedRuleIds.size());
        } catch (RuntimeException e) {
            log.warn("Could not report the progress of analysis {}", assessmentId, e);
        }
    }

    /** Model turns taken so far. */
    public int stepsTaken() {
        return stepsTaken.get();
    }

    /**
     * Marks the transcript as compacted.
     *
     * @return {@code true} the first time only, so the trace carries one note rather than one per
     *         turn for the rest of the run
     */
    public boolean recordCompaction() {
        return compactionAnnounced.compareAndSet(false, true);
    }

    // ------------------------------------------------------------------
    // Tool timings
    // ------------------------------------------------------------------

    public void recordTiming(String tool, long ms) {
        timings.add(new ToolTiming(tool, ms));
    }

    /** Takes the next recorded timing, matching by tool name when the queue is in step. */
    public Long takeTiming(String tool) {
        ToolTiming head = timings.peek();
        if (head == null) {
            return null;
        }
        if (!head.tool().equals(tool)) {
            // Should not happen - tools are executed in the order the model requested them - but a
            // mismatch must not corrupt every later step, so the queue is resynchronised.
            timings.poll();
            return null;
        }
        return timings.poll().ms();
    }

    private record ToolTiming(String tool, long ms) {
    }

    /** One rule's scope, kept as both a list (for row writing) and a set (for the id check). */
    private record Scope(List<UUID> ids, Set<UUID> set) {
    }
}
