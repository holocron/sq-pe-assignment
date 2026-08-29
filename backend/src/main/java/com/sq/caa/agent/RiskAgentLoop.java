package com.sq.caa.agent;

import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.rules.RuleEvaluationResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The reason/act loop and the rule-coverage gate that guards its exit.
 *
 * <p>Spring AI 2.x does not execute tools inside {@code ChatModel.call} - tool execution moved to a
 * ChatClient advisor - so calling the model directly hands the tool calls back unexecuted and this
 * class drives the loop itself. That is what makes the gate possible: nothing between the model and
 * the database gets to decide when the analysis is over except the code below.
 *
 * <h2>The rule-coverage gate</h2>
 * <ol>
 *   <li>The coverage set is fixed in the {@link AgentRunContext} before the first turn.</li>
 *   <li>{@code submit_rule_evaluation} is the only way into the evaluated set.</li>
 *   <li>If the model tries to conclude - by calling {@code submit_final_assessment} or simply by
 *       answering without any tool call - while a rule is still unevaluated, the loop does
 *       <b>not</b> exit. It records a {@code coverage_reprompt} step, appends a user message naming
 *       every outstanding rule by name and id, and continues.</li>
 *   <li>Reprompts are bounded by {@code caa.agent.max-coverage-reprompts} so a stubborn model cannot
 *       burn the entire step budget; exhausting them ends the loop, never the coverage.</li>
 *   <li>{@link #settle} then closes every rule of the coverage set against the deterministic engine:
 *       rules the agent skipped are evaluated outright and marked
 *       {@link RuleVerdictSource#DETERMINISTIC_FALLBACK}, rules it did rule on are cross-checked.
 *       Coverage is therefore 100% on every run, and {@code coverage_complete} records whether the
 *       agent got there by itself.</li>
 * </ol>
 *
 * <p>On disagreement the deterministic engine wins for scoring and the disagreement is written into
 * the trace: the model can add context, never remove risk.
 */
@Component
public class RiskAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(RiskAgentLoop.class);

    /** Used only if neither the properties nor the chat model name a model. */
    private static final String FALLBACK_MODEL = "gpt-oss-120b-GGUF";

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /** How often one run may replay a turn after the server refused the prompt as too large. */
    private static final int MAX_CONTEXT_RETRIES = 3;

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final JsonMapper jsonMapper;
    private final AgentProperties properties;
    private final ConversationCompactor compactor;

    public RiskAgentLoop(ChatModel chatModel, ToolCallingManager toolCallingManager,
            JsonMapper jsonMapper, AgentProperties properties) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.compactor = new ConversationCompactor(properties.promptBudgetTokens(),
                properties.keepRecentMessages());
    }

    /** Model id this loop will drive, recorded on the run before it starts. */
    public String modelId() {
        ChatOptions defaults = chatModel.getOptions();
        String configured = defaults == null ? null : defaults.getModel();
        return properties.modelOr(configured == null || configured.isBlank() ? FALLBACK_MODEL : configured);
    }

    /**
     * Runs the whole ReAct conversation and settles the coverage set. Blocking and slow by nature -
     * the caller must already be off the request thread.
     */
    public AgentRunResult execute(AgentRunContext context, RiskAgentTools tools) {
        long startedAt = System.currentTimeMillis();
        String model = modelId();
        context.trace().started(model, context.customer().getFullName(), context.ruleCount());
        int steps = 0;
        try {
            steps = converse(context, tools, model);
        } catch (RuntimeException e) {
            // The conversation died - but everything the agent had already submitted is still in the
            // context, so the run is settled from it rather than thrown away. The caller marks the
            // run FAILED with this cause; it does not have to re-do the work deterministically.
            int completed = context.stepsTaken();
            AgentRunResult partial = settle(context, completed, System.currentTimeMillis() - startedAt);
            throw new AgentRunFailedException(partial, e);
        }
        return settle(context, steps, System.currentTimeMillis() - startedAt);
    }

    // ------------------------------------------------------------------
    // The loop
    // ------------------------------------------------------------------

    private int converse(AgentRunContext context, RiskAgentTools tools, String model) {
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(tools));
        var options = OpenAiChatOptions.builder()
                // Must be the OpenAI options builder: OpenAiChatModel casts the prompt options to its
                // own type, and the generic ToolCallingChatOptions builder trips a ClassCastException
                // at call time.
                .toolCallbacks(callbacks)
                .model(model)
                .maxTokens(properties.maxTokens())
                .temperature(properties.temperature())
                .build();

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(AgentPrompts.system()));
        history.add(new UserMessage(AgentPrompts.task(context.customer(), context.rules())));

        int steps = 0;
        int coverageReprompts = 0;
        int conclusionReprompts = 0;
        int contextRetries = 0;

        while (steps < properties.maxSteps()) {
            // The schemas ride along with every request, so they are part of the budget, and the
            // budget is re-derived each turn because calibration keeps moving the estimate.
            history = fitToContext(context, history, compactor.estimateTools(callbacks));
            Prompt prompt = new Prompt(history, options);
            int estimated = compactor.estimate(history) + compactor.estimateTools(callbacks);
            // Counted before the call, not after: a turn the model server refuses is still a turn,
            // and a failed run must report how far it actually got.
            steps++;
            context.recordStep();
            ChatResponse response;
            try {
                response = chatModel.call(prompt);
            } catch (RuntimeException e) {
                // A context overflow is recoverable: the transcript is the only thing that grew, so
                // tighten the estimator and replay the turn against a harder-compacted history
                // instead of losing the run. Anything else is a real failure.
                if (isContextOverflow(e) && contextRetries++ < MAX_CONTEXT_RETRIES
                        && compactor.tighten()) {
                    log.warn("Analysis {}: the model server rejected the prompt as too large; "
                            + "compacting harder and retrying the turn", context.assessmentId(), e);
                    context.trace().reprompt("The model server refused the prompt as too large for "
                            + "its context window; the transcript was compacted further and the turn "
                            + "retried.");
                    continue;
                }
                throw e;
            }
            calibrate(context, estimated, response);

            AssistantMessage assistant = response.getResult() == null
                    ? null
                    : response.getResult().getOutput();
            String text = assistant == null ? null : assistant.getText();

            if (response.hasToolCalls()) {
                if (hasText(text)) {
                    context.trace().assistant(text);
                }
                ToolExecutionResult execution = toolCallingManager.executeToolCalls(prompt, response);
                List<Message> updated = new ArrayList<>(execution.conversationHistory());
                recordToolCalls(context, assistant, updated);
                history = updated;

                if (context.consumeConclusionRejected()) {
                    // The gate fired inside submit_final_assessment, which already recorded the
                    // coverage_reprompt step. Name the outstanding rules and keep going.
                    if (++coverageReprompts > properties.maxCoverageReprompts()) {
                        logExhausted(context);
                        break;
                    }
                    history.add(new UserMessage(AgentPrompts.coverageReprompt(context.missingRules())));
                    continue;
                }
                if (context.isConcluded()) {
                    break;
                }
                continue;
            }

            // No tool calls: the model considers itself finished.
            if (hasText(text)) {
                context.trace().assistant(text);
            }
            List<RiskRule> missing = context.missingRules();
            if (!missing.isEmpty()) {
                context.trace().coverageReprompt(
                        missing.stream().map(rule -> rule.getRuleId().toString()).toList(),
                        missing.stream().map(RiskRule::getRuleName).toList());
                if (++coverageReprompts > properties.maxCoverageReprompts()) {
                    logExhausted(context);
                    break;
                }
                if (assistant != null) {
                    history.add(assistant);
                }
                history.add(new UserMessage(AgentPrompts.coverageReprompt(missing)));
                continue;
            }
            if (!context.isConcluded()) {
                if (++conclusionReprompts > properties.maxCoverageReprompts()) {
                    context.trace().reprompt("The model stopped without submitting an assessment; the "
                            + "deterministic scores stand on their own.");
                    break;
                }
                context.trace().reprompt("Every rule has a verdict but no assessment was submitted; "
                        + "asking the model to conclude.");
                if (assistant != null) {
                    history.add(assistant);
                }
                history.add(new UserMessage(hasText(text)
                        ? AgentPrompts.conclusionReprompt()
                        : AgentPrompts.emptyTurnReprompt()));
                continue;
            }
            break;
        }
        return steps;
    }

    /**
     * Compacts the transcript when it is about to overflow the model server's context window.
     *
     * <p>Without this the loop dies mid-run: a full analysis is around thirty turns, each appending
     * an assistant message and a page of tool output, and the request is refused outright once the
     * window is exceeded. Compaction is logged and traced, because a reviewer reading the transcript
     * must be able to see that the model was working from an abridged history.
     */
    private List<Message> fitToContext(AgentRunContext context, List<Message> history, int toolTokens) {
        List<Message> compacted = compactor.compact(history, toolTokens);
        if (compacted == history) {
            return history;
        }
        int before = compactor.estimate(history) + toolTokens;
        int after = compactor.estimate(compacted) + toolTokens;
        log.info("Analysis {}: compacted the transcript from ~{} to ~{} tokens to fit the {}-token "
                + "prompt budget", context.assessmentId(), before, after,
                compactor.promptBudgetTokens());
        if (context.recordCompaction()) {
            context.trace().reprompt("The transcript outgrew the model's context window, so the "
                    + "oldest tool results were replaced by placeholders. The agent can call any "
                    + "tool again to get the same answer.");
        }
        return compacted;
    }

    /**
     * Feeds the server's real {@code prompt_tokens} back into the estimator.
     *
     * <p>The compactor's character heuristic decides when the transcript must be trimmed, and being
     * optimistic about it is what kills a run. Measuring the error on every turn turns a guess into
     * a controlled one.
     */
    private void calibrate(AgentRunContext context, int estimated, ChatResponse response) {
        Integer actual = response == null || response.getMetadata() == null
                || response.getMetadata().getUsage() == null
                        ? null
                        : response.getMetadata().getUsage().getPromptTokens();
        if (actual == null) {
            return;
        }
        double before = compactor.charsPerToken();
        compactor.calibrate(estimated, actual);
        if (compactor.charsPerToken() < before) {
            log.info("Analysis {}: prompt estimated at {} tokens, server counted {}; tightening the "
                    + "token estimate to {} characters per token", context.assessmentId(), estimated,
                    actual, String.format(Locale.ROOT, "%.2f", compactor.charsPerToken()));
        }
    }

    /** True when the failure is the model server refusing an over-long prompt. */
    private static boolean isContextOverflow(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }
            String text = message.toLowerCase(Locale.ROOT);
            if ((text.contains("context") && (text.contains("size") || text.contains("length")))
                    || text.contains("too many tokens")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private void logExhausted(AgentRunContext context) {
        log.warn("Analysis {}: coverage reprompt budget exhausted with {} rule(s) still unevaluated; "
                        + "the deterministic backfill will complete them",
                context.assessmentId(), context.missingRules().size());
    }

    /** Turns one round of executed tool calls into {@code tool_call} trace steps. */
    private void recordToolCalls(AgentRunContext context, AssistantMessage assistant,
            List<Message> conversation) {
        if (assistant == null || assistant.getToolCalls() == null) {
            return;
        }
        Map<String, String> results = new HashMap<>();
        for (Message message : conversation) {
            if (message instanceof ToolResponseMessage toolResponses) {
                for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
                    results.put(response.id(), response.responseData());
                }
            }
        }
        for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
            Long ms = context.takeTiming(call.name());
            context.trace().toolCall(call.name(), readJson(call.arguments()), results.get(call.id()),
                    ms == null ? 0L : ms);
        }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            return jsonMapper.readTree(json);
        } catch (RuntimeException e) {
            return JsonNodeFactory.instance.stringNode(json);
        }
    }

    // ------------------------------------------------------------------
    // Settling the coverage set
    // ------------------------------------------------------------------

    /**
     * Closes the coverage set: every rule ends with a verdict here. Rules the agent submitted are
     * cross-checked against the deterministic engine, rules it skipped are evaluated by the engine
     * outright. Called after every loop, and on its own when a run failed before the loop could
     * finish.
     */
    public AgentRunResult settle(AgentRunContext context, int steps, long durationMs) {
        boolean coverageComplete = context.coverageComplete();
        int evaluatedByAgent = context.evaluatedCount();
        List<RuleOutcome> outcomes = new ArrayList<>(context.ruleCount());
        BigDecimal total = ZERO;
        int disagreements = 0;

        for (RiskRule rule : context.rules()) {
            UUID ruleId = rule.getRuleId();
            RuleEvaluationResult engine = context.deterministic(ruleId);
            AgentRuleVerdict verdict = context.verdict(ruleId);
            RuleVerdictSource source = verdict == null
                    ? RuleVerdictSource.DETERMINISTIC_FALLBACK
                    : RuleVerdictSource.AGENT;

            if (verdict == null) {
                context.trace().backfill(ruleId, rule.getRuleName(), engine.triggered());
            }
            boolean disagreement = verdict != null && verdict.triggered() != engine.triggered();
            if (disagreement) {
                disagreements++;
                context.trace().disagreement(ruleId, rule.getRuleName(), verdict.triggered(),
                        engine.triggered());
            }

            BigDecimal score = engine.triggered() ? scale(engine.score()) : ZERO;
            total = total.add(score);
            outcomes.add(new RuleOutcome(
                    ruleId,
                    rule.getRuleName(),
                    engine.appliesTo(),
                    scale(rule.getWeight()),
                    engine.triggered(),
                    score,
                    source,
                    engine.evaluatedTransactionCount(),
                    engine.matchedCount(),
                    engine.matchedTransactionIds(),
                    context.batch().transactionIdsFor(rule.getAppliesTo()),
                    engine.degraded(),
                    engine.degradationNotes(),
                    engine.explanation(),
                    verdict == null ? null : verdict.rationale(),
                    verdict == null ? null : verdict.triggered(),
                    verdict == null ? null : verdict.score(),
                    disagreement));
        }

        RiskLevel banded = RiskLevel.forScore(total);
        FinalAssessment conclusion = context.finalAssessment();
        String summary = conclusion != null && conclusion.summary() != null
                ? conclusion.summary()
                : fallbackSummary(context, outcomes, total, banded);
        String recommendations = conclusion != null && conclusion.recommendations() != null
                ? conclusion.recommendations()
                : fallbackRecommendations(outcomes, banded);

        context.trace().finalStep(banded.name(), summary, total, context.ruleCount(), coverageComplete);

        return new AgentRunResult(
                context.assessmentId(),
                banded,
                conclusion == null ? null : conclusion.riskLevel(),
                total,
                summary,
                recommendations,
                outcomes,
                context.ruleCount(),
                evaluatedByAgent,
                coverageComplete,
                disagreements,
                steps,
                modelId(),
                durationMs);
    }

    /**
     * Narrative used when the model never submitted one. The run is still complete and scored - the
     * deterministic engine covered every rule - so it is reported rather than discarded.
     */
    private static String fallbackSummary(AgentRunContext context, List<RuleOutcome> outcomes,
            BigDecimal total, RiskLevel banded) {
        List<RuleOutcome> triggered = outcomes.stream().filter(RuleOutcome::triggered).toList();
        StringJoiner names = new StringJoiner(", ");
        triggered.forEach(outcome -> names.add(outcome.ruleName()));
        String body = triggered.isEmpty()
                ? "No applicable rule was breached."
                : triggered.size() + " of " + outcomes.size() + " applicable rules were breached: "
                        + names + ".";
        return "The AI analyst did not submit a written assessment, so this summary was generated from "
                + "the deterministic rule engine. " + body + " Total score " + total.toPlainString()
                + ", banded " + banded + ". All " + context.ruleCount()
                + " applicable rules were evaluated, so the coverage below is complete.";
    }

    private static String fallbackRecommendations(List<RuleOutcome> outcomes, RiskLevel banded) {
        if (outcomes.stream().noneMatch(RuleOutcome::triggered)) {
            return "No action required beyond the standard periodic review.";
        }
        return switch (banded) {
            case CRITICAL -> "Escalate to the money laundering reporting officer immediately and "
                    + "consider restricting the account.\n"
                    + "Review every transaction listed against the triggered rules.\n"
                    + "Decide on a suspicious activity report within 24 hours.";
            case HIGH -> "Open a manual investigation into the triggered rules.\n"
                    + "Request source-of-funds evidence from the customer.\n"
                    + "Re-review once the evidence has been received.";
            case MEDIUM -> "Place the customer under enhanced monitoring.\n"
                    + "Re-assess after the next 30 days of activity.";
            case LOW -> "Record the triggered rule and keep the customer on standard monitoring.";
        };
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
