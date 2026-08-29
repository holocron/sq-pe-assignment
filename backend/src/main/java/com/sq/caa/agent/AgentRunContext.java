package com.sq.caa.agent;

import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.rules.EvaluationBatch;
import com.sq.caa.rules.RuleEvaluationResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Mutable state of one analysis run, shared between the ReAct loop and the tools it exposes.
 *
 * <p>This is where rule coverage is tracked. The {@code coverageSet} is fixed before the first model
 * turn; {@code verdicts} only ever grows through {@code submit_rule_evaluation}; and
 * {@link #missingRules()} is the difference the loop refuses to finish with. Nothing here can widen
 * or shrink the coverage set once the run has started, so the gate cannot be talked out of by the
 * model.
 *
 * <p>Deterministic evaluations are memoised: the agent typically calls
 * {@code evaluate_rule_deterministically} for a rule and the post-loop cross-check needs the same
 * verdict, and every evaluation runs against one immutable {@link EvaluationBatch}, so re-running it
 * could only waste CPU, never change the answer.
 *
 * <p>All mutable collections are concurrent because tool execution order is decided by the model and
 * the framework, not by this class.
 */
public final class AgentRunContext {

    private final UUID assessmentId;
    private final Customer customer;
    private final EvaluationBatch batch;
    private final Map<UUID, RiskRule> coverageSet;
    /** Coverage-set order, fixed at construction so every listing and every reprompt agrees. */
    private final List<UUID> orderedRuleIds;
    private final Function<RiskRule, RuleEvaluationResult> evaluator;
    private final AnalysisTrace trace;

    private final Map<UUID, RuleEvaluationResult> deterministic = new ConcurrentHashMap<>();
    private final Map<UUID, AgentRuleVerdict> verdicts = new ConcurrentHashMap<>();
    private final Queue<ToolTiming> timings = new ConcurrentLinkedQueue<>();
    private final AtomicReference<FinalAssessment> finalAssessment = new AtomicReference<>();
    private final AtomicBoolean finalRejected = new AtomicBoolean();
    private final AtomicBoolean compactionAnnounced = new AtomicBoolean();
    private final AtomicInteger stepsTaken = new AtomicInteger();

    public AgentRunContext(UUID assessmentId, Customer customer, EvaluationBatch batch,
            Collection<RiskRule> coverageSet, Function<RiskRule, RuleEvaluationResult> evaluator,
            AnalysisTrace trace) {
        this.assessmentId = assessmentId;
        this.customer = customer;
        this.batch = batch;
        this.evaluator = evaluator;
        this.trace = trace;
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
    }

    public AgentRuleVerdict verdict(UUID ruleId) {
        return verdicts.get(ruleId);
    }

    // ------------------------------------------------------------------
    // Deterministic engine
    // ------------------------------------------------------------------

    /** Runs (or replays) the deterministic engine for one rule of the coverage set. */
    public RuleEvaluationResult deterministic(UUID ruleId) {
        RiskRule rule = coverageSet.get(ruleId);
        if (rule == null) {
            return null;
        }
        return deterministic.computeIfAbsent(ruleId, key -> evaluator.apply(rule));
    }

    /** Whether the agent already asked the engine about this rule. */
    public boolean hasDeterministic(UUID ruleId) {
        return deterministic.containsKey(ruleId);
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
}
