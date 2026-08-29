package com.sq.caa.agent;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
 * the stream is only a view of it.
 */
public final class AnalysisTrace {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTrace.class);

    /** SSE event carrying one {@link TraceStep}. */
    public static final String EVENT_STEP = "step";

    /** SSE event carrying the run header / status transitions. */
    public static final String EVENT_STATUS = "status";

    private final UUID assessmentId;
    private final JsonNodeFactory nodes;
    private final List<TraceStep> steps = new ArrayList<>();
    private final List<SseEmitter> subscribers = new ArrayList<>();
    private final Object lock = new Object();
    private String lastStatusEvent;
    private boolean closed;

    public AnalysisTrace(UUID assessmentId, JsonNodeFactory nodes) {
        this.assessmentId = assessmentId;
        this.nodes = nodes == null ? JsonNodeFactory.instance : nodes;
    }

    public UUID assessmentId() {
        return assessmentId;
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
                .text("Analysing " + customerName + " with " + model + "; " + ruleCount
                        + " applicable rule(s) must each receive a verdict."));
    }

    public TraceStep assistant(String text) {
        return add(builder(TraceStep.Type.ASSISTANT).text(TraceStep.truncate(text, TraceStep.TEXT_LIMIT)));
    }

    public TraceStep toolCall(String tool, JsonNode args, String resultPreview, long ms) {
        return add(builder(TraceStep.Type.TOOL_CALL)
                .tool(tool)
                .args(args)
                .resultPreview(TraceStep.truncate(resultPreview, TraceStep.PREVIEW_LIMIT))
                .ms(ms));
    }

    /** The gate fired: the model tried to conclude while these rules had no verdict. */
    public TraceStep coverageReprompt(Collection<String> missingRuleIds, Collection<String> missingRuleNames) {
        ObjectNode detail = nodes.objectNode();
        ArrayNode names = detail.putArray("missing_rule_names");
        missingRuleNames.forEach(names::add);
        return add(builder(TraceStep.Type.COVERAGE_REPROMPT)
                .missing(List.copyOf(missingRuleIds))
                .detail(detail)
                .text("Coverage gate: " + missingRuleIds.size() + " rule(s) still have no verdict - "
                        + String.join(", ", missingRuleNames)
                        + ". The model was told to keep working instead of concluding."));
    }

    public TraceStep reprompt(String text) {
        return add(builder(TraceStep.Type.REPROMPT).text(text));
    }

    public TraceStep disagreement(UUID ruleId, String ruleName, boolean agentTriggered,
            boolean deterministicTriggered) {
        ObjectNode detail = nodes.objectNode();
        detail.put("rule_id", ruleId.toString());
        detail.put("rule_name", ruleName);
        detail.put("agent_triggered", agentTriggered);
        detail.put("deterministic_triggered", deterministicTriggered);
        return add(builder(TraceStep.Type.DISAGREEMENT)
                .detail(detail)
                .text("Cross-check on '" + ruleName + "': the agent said "
                        + (agentTriggered ? "triggered" : "not triggered") + " but the rule engine said "
                        + (deterministicTriggered ? "triggered" : "not triggered")
                        + ". The deterministic result wins for scoring."));
    }

    public TraceStep backfill(UUID ruleId, String ruleName, boolean triggered) {
        ObjectNode detail = nodes.objectNode();
        detail.put("rule_id", ruleId.toString());
        detail.put("rule_name", ruleName);
        detail.put("triggered", triggered);
        detail.put("source", RuleVerdictSource.DETERMINISTIC_FALLBACK.name());
        return add(builder(TraceStep.Type.BACKFILL)
                .detail(detail)
                .text("Rule '" + ruleName + "' never received an agent verdict and was evaluated by the "
                        + "deterministic engine instead (" + (triggered ? "triggered" : "not triggered")
                        + ")."));
    }

    public TraceStep finalStep(String riskLevel, String summary, BigDecimal totalScore,
            int rulesTotal, boolean coverageComplete) {
        ObjectNode detail = nodes.objectNode();
        detail.put("total_score", totalScore == null ? null : totalScore.toPlainString());
        detail.put("rules_total", rulesTotal);
        detail.put("coverage_complete", coverageComplete);
        return add(builder(TraceStep.Type.FINAL)
                .riskLevel(riskLevel)
                .detail(detail)
                .text(TraceStep.truncate(summary, TraceStep.TEXT_LIMIT)));
    }

    public TraceStep error(String message) {
        return add(builder(TraceStep.Type.ERROR).text(TraceStep.truncate(message, TraceStep.TEXT_LIMIT)));
    }

    private StepBuilder builder(String type) {
        return new StepBuilder(type);
    }

    private TraceStep add(StepBuilder builder) {
        TraceStep step;
        List<SseEmitter> targets;
        String payload;
        synchronized (lock) {
            step = builder.build(steps.size() + 1);
            steps.add(step);
            payload = step.toJson(nodes).toString();
            targets = List.copyOf(subscribers);
        }
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
     */
    public boolean subscribe(SseEmitter emitter, String statusPayload) {
        List<String> replay;
        boolean live;
        String status;
        synchronized (lock) {
            replay = steps.stream().map(step -> step.toJson(nodes).toString()).toList();
            status = lastStatusEvent != null ? lastStatusEvent : statusPayload;
            live = !closed;
            if (live) {
                subscribers.add(emitter);
                emitter.onCompletion(() -> unsubscribe(emitter));
                emitter.onTimeout(() -> {
                    unsubscribe(emitter);
                    emitter.complete();
                });
                emitter.onError(error -> unsubscribe(emitter));
            }
        }
        if (status != null) {
            send(emitter, EVENT_STATUS, status);
        }
        for (String step : replay) {
            send(emitter, EVENT_STEP, step);
        }
        return live;
    }

    private void unsubscribe(SseEmitter emitter) {
        synchronized (lock) {
            subscribers.remove(emitter);
        }
    }

    /** Broadcasts a status transition and remembers it so late subscribers still see it. */
    public void publishStatus(String payload) {
        List<SseEmitter> targets;
        synchronized (lock) {
            lastStatusEvent = payload;
            targets = List.copyOf(subscribers);
        }
        dispatch(targets, EVENT_STATUS, payload);
    }

    /** Closes the stream: no further steps are accepted for fan-out and every client is completed. */
    public void close() {
        List<SseEmitter> targets;
        synchronized (lock) {
            closed = true;
            targets = List.copyOf(subscribers);
            subscribers.clear();
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.debug("Could not complete SSE subscriber of analysis {}", assessmentId, e);
            }
        }
    }

    private void dispatch(List<SseEmitter> targets, String event, String payload) {
        for (SseEmitter emitter : targets) {
            send(emitter, event, payload);
        }
    }

    private void send(SseEmitter emitter, String event, String payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (IOException | IllegalStateException e) {
            // The client went away mid-run. Drop it; the analysis itself is unaffected.
            log.debug("Dropping SSE subscriber of analysis {}: {}", assessmentId, e.toString());
            unsubscribe(emitter);
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

        private TraceStep build(int n) {
            return new TraceStep(n, type, Instant.now(), tool, args, resultPreview, ms, text, missing,
                    riskLevel, detail);
        }
    }
}
