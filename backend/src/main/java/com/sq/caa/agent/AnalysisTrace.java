package com.sq.caa.agent;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The growing ReAct transcript of one run, and the live fan-out of that transcript to SSE clients.
 *
 * <p>Recording and subscribing are guarded by the same monitor. That is what makes the stream
 * gap-free: a client that connects mid-run receives the steps recorded so far and is registered for
 * future ones in a single atomic action, so no step can slip between the replay and the
 * registration.
 *
 * <p>A dead subscriber is dropped rather than allowed to fail the run - the analysis is the product,
 * the stream is only a view of it. That principle also decides who does the writing: recording a
 * step never touches a socket. Each subscriber owns a bounded queue and is drained on a shared
 * fan-out executor, so a browser that has stopped reading - a suspended tab, a paused proxy, a full
 * TCP window - can at worst fill its own queue and be dropped. It can never hold up the analysis
 * thread, and it cannot delay the other subscribers of the same run either.
 */
public final class AnalysisTrace {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTrace.class);

    /** SSE event carrying one {@link TraceStep}. */
    public static final String EVENT_STEP = "step";

    /** SSE event carrying the run header / status transitions. */
    public static final String EVENT_STATUS = "status";

    /**
     * Events one subscriber may fall behind by before it is dropped. Generous next to a run's ~40
     * steps, so only a client that has genuinely stopped reading ever hits it - and dropping it is
     * harmless, because reconnecting replays the whole transcript.
     */
    private static final int MAX_QUEUED_EVENTS = 512;

    /** Queue marker meaning "nothing more is coming; complete this client". */
    private static final String[] COMPLETE = new String[0];

    private final UUID assessmentId;
    private final JsonNodeFactory nodes;
    private final Executor dispatcher;
    private final List<TraceStep> steps = new ArrayList<>();
    private final List<Subscription> subscribers = new ArrayList<>();
    private final Object lock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean cancellationRequested =
            new java.util.concurrent.atomic.AtomicBoolean();
    private String lastStatusEvent;
    private boolean closed;

    /**
     * A transcript that fans out on the calling thread. Only for callers that never subscribe -
     * tests, and any path that records without streaming; production goes through
     * {@link AnalysisStreamRegistry}, which supplies the fan-out executor.
     */
    public AnalysisTrace(UUID assessmentId, JsonNodeFactory nodes) {
        this(assessmentId, nodes, Runnable::run);
    }

    public AnalysisTrace(UUID assessmentId, JsonNodeFactory nodes, Executor dispatcher) {
        this.assessmentId = assessmentId;
        this.nodes = nodes == null ? JsonNodeFactory.instance : nodes;
        this.dispatcher = dispatcher == null ? Runnable::run : dispatcher;
    }

    public UUID assessmentId() {
        return assessmentId;
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    /**
     * The run's cancellation flag rides the per-run trace: it is the one object the cancel endpoint
     * and the agent loop both already hold. The loop polls it between turns and aborts promptly.
     */
    public void requestCancellation() {
        cancellationRequested.set(true);
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }

    /** Immutable snapshot of the transcript so far. */
    public List<TraceStep> steps() {
        synchronized (lock) {
            return List.copyOf(steps);
        }
    }

    public int size() {
        synchronized (lock) {
            return steps.size();
        }
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    public TraceStep started(String model, String customerName, int ruleCount) {
        return add(builder(TraceStep.Type.STARTED)
                .subject(customerName)
                .outcome(ruleCount + " rule(s) to judge")
                .text("Analysing " + customerName + " with " + model + "; " + ruleCount
                        + " applicable rule(s) must each receive a verdict."));
    }

    public TraceStep assistant(String text) {
        return add(builder(TraceStep.Type.ASSISTANT).text(TraceStep.truncate(text, TraceStep.TEXT_LIMIT)));
    }

    /**
     * One executed tool call.
     *
     * <p>{@code note} is what makes the step legible while collapsed: twelve rules produce two dozen
     * steps whose tool name is the same, so the rule being judged - or the transaction opened, or
     * the query searched - is recorded on the step itself rather than being left inside the
     * arguments. It may be null for a tool whose call is not scoped to anything nameable.
     *
     * <p>When the note carries SQL it is written into {@code detail.sql}. That is the whole audit
     * trail of a verdict: the rule's condition was answered by a query, so a reviewer has to be able
     * to read the query - on the attempt that was accepted and on every attempt that was rejected
     * along the way.
     */
    public TraceStep toolCall(String tool, JsonNode args, String resultPreview, long ms,
            TraceStep.Note note) {
        return add(builder(TraceStep.Type.TOOL_CALL)
                .tool(tool)
                .args(args)
                .resultPreview(TraceStep.truncate(resultPreview, TraceStep.PREVIEW_LIMIT))
                .ms(ms)
                .detail(note == null || note.sql() == null ? null : nodes.objectNode()
                        .put("sql", note.sql()))
                .note(note));
    }

    /** The gate fired: the model tried to conclude while these rules had no verdict. */
    public TraceStep coverageReprompt(Collection<String> missingRuleIds, Collection<String> missingRuleNames) {
        ObjectNode detail = nodes.objectNode();
        ArrayNode names = detail.putArray("missing_rule_names");
        missingRuleNames.forEach(names::add);
        return add(builder(TraceStep.Type.COVERAGE_REPROMPT)
                .missing(List.copyOf(missingRuleIds))
                .detail(detail)
                .outcome(missingRuleIds.size() + " rule(s) still unjudged")
                .text("Coverage gate: " + missingRuleIds.size() + " rule(s) still have no verdict - "
                        + String.join(", ", missingRuleNames)
                        + ". The model was told to keep working instead of concluding."));
    }

    public TraceStep reprompt(String text) {
        return add(builder(TraceStep.Type.REPROMPT).text(text));
    }

    /**
     * The run ended with applicable rules still unjudged.
     *
     * <p>Recorded on the way out of a failed run, before the result is persisted, so the transcript
     * a reviewer opens names the rules that were never looked at rather than merely showing a
     * coverage counter that stops short.
     */
    public TraceStep coverageFailed(int rulesTotal, Collection<String> unjudgedRuleIds,
            Collection<String> unjudgedRuleNames) {
        ObjectNode detail = nodes.objectNode();
        detail.put("rules_total", rulesTotal);
        detail.put("rules_unjudged", unjudgedRuleIds.size());
        ArrayNode names = detail.putArray("unjudged_rule_names");
        unjudgedRuleNames.forEach(names::add);
        return add(builder(TraceStep.Type.COVERAGE_FAILED)
                .missing(List.copyOf(unjudgedRuleIds))
                .detail(detail)
                .outcome(unjudgedRuleIds.size() + " of " + rulesTotal + " never judged")
                .text("Coverage failure: " + unjudgedRuleIds.size() + " of " + rulesTotal
                        + " applicable rule(s) never received a verdict - "
                        + String.join(", ", unjudgedRuleNames)
                        + ". This analysis is recorded as FAILED; the verdicts already obtained are "
                        + "kept, but the review is not complete and must be run again."));
    }

    /**
     * The model wrote its conclusion as prose instead of calling {@code submit_final_assessment},
     * and the loop accepted it because every rule already had a verdict.
     */
    public TraceStep proseFinal(String riskLevel, String summary) {
        ObjectNode detail = nodes.objectNode();
        detail.put("risk_level", riskLevel);
        detail.put("source", "assistant_message");
        return add(builder(TraceStep.Type.PROSE_FINAL)
                .riskLevel(riskLevel)
                .detail(detail)
                .text("The model wrote its final assessment as prose instead of calling "
                        + "submit_final_assessment. Every rule already had a verdict, so the "
                        + "assessment was parsed out of the message and accepted rather than "
                        + "costing another round trip. Proposed level: " + riskLevel + ". "
                        + TraceStep.truncate(summary, TraceStep.TEXT_LIMIT / 4)));
    }

    /**
     * Terminal step: the recorded band, the band the rule scores band to, and the total.
     *
     * <p>Both bands are on the step because they can legitimately differ. The mechanical band is
     * arithmetic over SQL-derived scores; the recorded band is that one unless the agent escalated
     * above it, which it may only do with a justification, which is written here beside it.
     */
    public TraceStep finalStep(String riskLevel, String mechanicalRiskLevel,
            String escalationJustification, String summary, BigDecimal totalScore, int rulesTotal,
            boolean coverageComplete) {
        ObjectNode detail = nodes.objectNode();
        detail.put("total_score", totalScore == null ? null : totalScore.toPlainString());
        detail.put("rules_total", rulesTotal);
        detail.put("coverage_complete", coverageComplete);
        detail.put("mechanical_risk_level", mechanicalRiskLevel);
        detail.put("escalated", escalationJustification != null);
        if (escalationJustification != null) {
            detail.put("escalation_justification", escalationJustification);
        }
        String narrative = TraceStep.truncate(summary, TraceStep.TEXT_LIMIT);
        String escalation = escalationJustification == null ? "" : "Escalated from "
                + mechanicalRiskLevel + " to " + riskLevel + ": " + escalationJustification;
        return add(builder(TraceStep.Type.FINAL)
                .riskLevel(riskLevel)
                .detail(detail)
                .outcome(escalationJustification == null
                        ? riskLevel + " (" + mechanicalRiskLevel + " from the rule scores)"
                        : "escalated " + mechanicalRiskLevel + " to " + riskLevel)
                .text(narrative == null ? escalation
                        : escalation.isEmpty() ? narrative : narrative + " " + escalation));
    }

    public TraceStep error(String message) {
        return add(builder(TraceStep.Type.ERROR).text(TraceStep.truncate(message, TraceStep.TEXT_LIMIT)));
    }

    /** The run was aborted at the user's request; recorded so the transcript says why it stopped. */
    public TraceStep cancelled() {
        return add(builder(TraceStep.Type.CANCELLED)
                .text("The analysis was cancelled at the user's request. The verdicts obtained so "
                        + "far are kept and the run is recorded as CANCELLED."));
    }

    private StepBuilder builder(String type) {
        return new StepBuilder(type);
    }

    private TraceStep add(StepBuilder builder) {
        TraceStep step;
        List<Subscription> targets;
        String payload;
        synchronized (lock) {
            step = builder.build(steps.size() + 1);
            steps.add(step);
            payload = step.toJson(nodes).toString();
            targets = List.copyOf(subscribers);
        }
        // Queue only - the actual socket writes happen on the fan-out executor, so this returns at
        // the speed of an ArrayDeque no matter how slowly the browsers are reading.
        dispatch(targets, EVENT_STEP, payload);
        return step;
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    /**
     * Replays the transcript into {@code emitter} and, when the run is still open, registers it for
     * every later step. Returns {@code false} when the run has already finished, in which case the
     * caller must complete the emitter itself.
     *
     * <p>Registration and replay happen under the same monitor and into the same per-subscriber
     * queue, so the stream is gap-free <em>and</em> in order: a step recorded while the replay is
     * still being written cannot overtake it.
     */
    public boolean subscribe(SseEmitter emitter, String statusPayload) {
        Subscription subscription;
        synchronized (lock) {
            if (closed) {
                return false;
            }
            subscription = new Subscription(emitter);
            subscribers.add(subscription);
            emitter.onCompletion(() -> unsubscribe(subscription));
            emitter.onTimeout(() -> {
                unsubscribe(subscription);
                emitter.complete();
            });
            emitter.onError(error -> unsubscribe(subscription));
            String status = lastStatusEvent != null ? lastStatusEvent : statusPayload;
            if (status != null) {
                subscription.offer(EVENT_STATUS, status);
            }
            for (TraceStep step : steps) {
                subscription.offer(EVENT_STEP, step.toJson(nodes).toString());
            }
        }
        subscription.kick();
        return true;
    }

    private void unsubscribe(Subscription subscription) {
        synchronized (lock) {
            subscribers.remove(subscription);
        }
    }

    /** Broadcasts a status transition and remembers it so late subscribers still see it. */
    public void publishStatus(String payload) {
        List<Subscription> targets;
        synchronized (lock) {
            lastStatusEvent = payload;
            targets = List.copyOf(subscribers);
        }
        dispatch(targets, EVENT_STATUS, payload);
    }

    /**
     * Closes the stream: no further steps are accepted for fan-out and every client is completed
     * once whatever it has already been sent has drained.
     */
    public void close() {
        List<Subscription> targets;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            targets = List.copyOf(subscribers);
            subscribers.clear();
        }
        for (Subscription subscription : targets) {
            subscription.offer(null, null);
            subscription.kick();
        }
    }

    /** Subscribers currently attached; used by the tests that assert nothing is left behind. */
    public int subscriberCount() {
        synchronized (lock) {
            return subscribers.size();
        }
    }

    private void dispatch(List<Subscription> targets, String event, String payload) {
        for (Subscription subscription : targets) {
            subscription.offer(event, payload);
            subscription.kick();
        }
    }

    /**
     * One SSE client, its backlog and the guarantee that exactly one thread writes to it at a time.
     *
     * <p>{@link #offer} is called from the analysis thread and never blocks; {@link #drain} runs on
     * the fan-out executor and does the blocking write. Ordering is preserved because a subscriber
     * is only ever being drained by one task, which the {@code draining} flag enforces.
     */
    private final class Subscription {

        private final SseEmitter emitter;
        private final Deque<String[]> pending = new ArrayDeque<>();
        private boolean draining;
        private boolean finished;

        private Subscription(SseEmitter emitter) {
            this.emitter = emitter;
        }

        /** Queues one event, or the completion sentinel when {@code event} is null. */
        private void offer(String event, String payload) {
            synchronized (this) {
                if (finished) {
                    return;
                }
                if (event != null && pending.size() >= MAX_QUEUED_EVENTS) {
                    // The client has stopped reading. Drop it rather than grow without bound; it can
                    // reconnect and the replay will give it everything it missed.
                    log.info("SSE subscriber of analysis {} fell {} events behind and was dropped",
                            assessmentId, pending.size());
                    pending.clear();
                    pending.add(COMPLETE);
                    return;
                }
                pending.add(event == null ? COMPLETE : new String[] {event, payload});
            }
        }

        /** Makes sure a drain task is running for this subscriber, without ever blocking. */
        private void kick() {
            synchronized (this) {
                if (draining || finished || pending.isEmpty()) {
                    return;
                }
                draining = true;
            }
            try {
                dispatcher.execute(this::drain);
            } catch (RuntimeException e) {
                // Executor gone (shutdown). Give the queue back so nothing is silently lost if
                // another kick arrives, and stop pretending we are draining.
                synchronized (this) {
                    draining = false;
                }
                log.debug("Could not schedule the SSE fan-out of analysis {}", assessmentId, e);
            }
        }

        private void drain() {
            while (true) {
                String[] event;
                synchronized (this) {
                    if (finished || pending.isEmpty()) {
                        draining = false;
                        return;
                    }
                    event = pending.poll();
                }
                if (event.length == 0) {
                    complete();
                    return;
                }
                if (!send(event[0], event[1])) {
                    return;
                }
            }
        }

        /** @return false when the client went away and this subscriber is done */
        private boolean send(String event, String payload) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
                return true;
            } catch (IOException | RuntimeException e) {
                // The client went away mid-run. Drop it; the analysis itself is unaffected.
                log.debug("Dropping SSE subscriber of analysis {}: {}", assessmentId, e.toString());
                discard();
                unsubscribe(this);
                return false;
            }
        }

        private void complete() {
            discard();
            unsubscribe(this);
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.debug("Could not complete SSE subscriber of analysis {}", assessmentId, e);
            }
        }

        private void discard() {
            synchronized (this) {
                finished = true;
                draining = false;
                pending.clear();
            }
        }
    }

    // ------------------------------------------------------------------
    // Serialisation
    // ------------------------------------------------------------------

    /** The transcript as {@code {"steps":[...]}} - the exact shape of {@code analysis_runs.trace}. */
    public ObjectNode toJson() {
        ObjectNode root = nodes.objectNode();
        ArrayNode array = root.putArray("steps");
        for (TraceStep step : steps()) {
            array.add(step.toJson(nodes));
        }
        return root;
    }

    public JsonNodeFactory nodes() {
        return nodes;
    }

    /** Mutable staging of one step; kept private so callers go through the named factories above. */
    private final class StepBuilder {

        private final String type;
        private String tool;
        private JsonNode args;
        private String resultPreview;
        private Long ms;
        private String text;
        private List<String> missing;
        private String riskLevel;
        private JsonNode detail;
        private String subject;
        private String outcome;

        private StepBuilder(String type) {
            this.type = type;
        }

        private StepBuilder tool(String value) {
            this.tool = value;
            return this;
        }

        private StepBuilder args(JsonNode value) {
            this.args = value;
            return this;
        }

        private StepBuilder resultPreview(String value) {
            this.resultPreview = value;
            return this;
        }

        private StepBuilder ms(long value) {
            this.ms = value;
            return this;
        }

        private StepBuilder text(String value) {
            this.text = value;
            return this;
        }

        private StepBuilder missing(List<String> value) {
            this.missing = value;
            return this;
        }

        private StepBuilder riskLevel(String value) {
            this.riskLevel = value;
            return this;
        }

        private StepBuilder detail(JsonNode value) {
            this.detail = value;
            return this;
        }

        private StepBuilder subject(String value) {
            this.subject = value;
            return this;
        }

        private StepBuilder outcome(String value) {
            this.outcome = value;
            return this;
        }

        /** Applies a tool's note, if it produced one; a null note leaves the step as it was. */
        private StepBuilder note(TraceStep.Note value) {
            if (value == null) {
                return this;
            }
            return subject(value.subject()).outcome(value.outcome());
        }

        private TraceStep build(int n) {
            return new TraceStep(n, type, Instant.now(), tool, args, resultPreview, ms, text, missing,
                    riskLevel, detail, subject, outcome);
        }
    }
}
