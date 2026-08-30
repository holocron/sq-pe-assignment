package com.sq.caa.agent;

import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
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
 * <h2>The rule-coverage guarantee</h2>
 * <p>A rule condition is prose; the model reads it, writes the SELECT that answers it and
 * {@code evaluate_rule} runs that query. There is no engine left to quietly close a rule the model
 * skipped, so coverage is guaranteed by refusing to call such a run finished rather than by filling
 * the gap.
 * <ol>
 *   <li>The coverage set is fixed in the {@link AgentRunContext} before the first turn.</li>
 *   <li>{@code evaluate_rule} is the only way into the evaluated set, and only a query that actually
 *       executed gets a rule in. A rejected or failed query records nothing.</li>
 *   <li>If the model tries to conclude - by calling {@code submit_final_assessment} or simply by
 *       answering without any tool call - while a rule is still unjudged, the loop does <b>not</b>
 *       exit. It records a {@code coverage_reprompt} step, appends a user message naming every
 *       outstanding rule by name and id, and continues.</li>
 *   <li>Reprompts are bounded by {@code caa.agent.max-coverage-reprompts} and turns by
 *       {@code caa.agent.max-steps}, so a stubborn model cannot burn the whole budget arguing.</li>
 *   <li>{@link #settle} then builds one outcome per rule the agent judged. If any rule is left over,
 *       {@link #execute} throws {@link AgentRunFailedException} carrying that partial result and an
 *       {@link IncompleteRuleCoverageException} naming the unjudged rules: the caller persists the
 *       run as {@code FAILED}, keeping every verdict that was obtained. A partial analysis is never
 *       reported as {@code COMPLETED}.</li>
 * </ol>
 *
 * <h2>Which band is recorded</h2>
 * <p>{@link #settle} sums the per-rule scores - each one a rule's weight because that rule's query
 * returned rows, or zero because it did not - and bands the total. That mechanical band is the floor.
 * The agent's own proposal is honoured only when it is <em>higher</em> and comes with a
 * justification, which is recorded beside it; anything lower is discarded here exactly as the tool
 * refuses it, so a narrative can never talk a scored breach down into a clean review.
 *
 * <p>The cost of this design is stated rather than hidden: the model writes the query afresh each
 * run, so two runs of the same customer can differ. What does not vary is what happens once a query
 * has run - the verdict is its row count and the score is the rule's weight, never an estimate - and
 * the coverage claim: a {@code COMPLETED} run has a verdict for every applicable rule, or it is not
 * {@code COMPLETED}.
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
     * Runs the whole ReAct conversation. Blocking and slow by nature - the caller must already be off
     * the request thread.
     *
     * @return the settled run, only ever with every applicable rule judged
     * @throws AgentRunFailedException when the conversation broke, or when it ended with rules still
     *                                 unjudged; {@link AgentRunFailedException#result()} carries
     *                                 everything the agent did establish
     */
    public AgentRunResult execute(AgentRunContext context, RiskAgentTools tools) {
        long startedAt = System.currentTimeMillis();
        String model = modelId();
        context.trace().started(model, context.customer().getFullName(), context.ruleCount());
        int steps;
        try {
            steps = converse(context, tools, model);
        } catch (RuntimeException e) {
            // The conversation died - but everything the agent had already submitted is still in the
            // context, so the run is settled from it rather than thrown away. The caller marks the
            // run FAILED with this cause and keeps the verdicts that were obtained.
            AgentRunResult partial = settle(context, context.stepsTaken(),
                    System.currentTimeMillis() - startedAt);
            throw new AgentRunFailedException(partial, e);
        }
        AgentRunResult result = settle(context, steps, System.currentTimeMillis() - startedAt);
        if (!result.coverageComplete()) {
            // The gate's last line. The loop is out of turns or out of reprompts and rules are still
            // unjudged; there is no backfill to close them, so this run must not be reported as a
            // finished analysis.
            log.warn("Analysis {}: {} of {} rule(s) never received a verdict; the run is recorded as "
                            + "FAILED with the {} verdict(s) it did obtain", context.assessmentId(),
                    result.unjudgedRules().size(), result.rulesTotal(), result.rulesJudged());
            throw new AgentRunFailedException(result, new IncompleteRuleCoverageException(
                    result.rulesTotal(), result.unjudgedRules(), result.unjudgedRuleNames()));
        }
        return result;
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
                // Not optional. OpenAiChatModel copies the options' timeout and retry count into the
                // SDK's per-request options, and this builder defaults them to 60 seconds and three
                // retries - which silently overrides spring.ai.openai.timeout for every call that
                // supplies its own options. A turn of this model routinely needs more than a minute,
                // so the default cut every long turn short and retried it, and the run died with
                // "OpenAIIoException: Request failed" having judged nothing.
                .timeout(properties.requestTimeout())
                .maxRetries(1)
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
                recordToolCalls(context, tools, assistant, updated);
                history = updated;

                if (context.consumeConclusionRejected()) {
                    // The gate fired inside submit_final_assessment, which already recorded the
                    // coverage_reprompt step. Name the outstanding rules and keep going.
                    List<RiskRule> stillMissing = context.missingRules();
                    if (stillMissing.isEmpty()) {
                        // A later tool call in the same batch closed the coverage set - the model
                        // emitted [submit_final_assessment, evaluate_rule] in one turn.
                        // Telling it that "0 rule(s) still have no verdict" would be a lie and would
                        // burn a coverage reprompt on a run that is now ready to conclude; ask it for
                        // the conclusion instead.
                        if (++conclusionReprompts > properties.maxCoverageReprompts()) {
                            context.trace().reprompt("The model kept concluding before its own last "
                                    + "verdict landed; every rule has a verdict, so the summary is "
                                    + "generated from the verdicts themselves.");
                            break;
                        }
                        context.trace().reprompt("The final assessment was rejected because a rule was "
                                + "still open when it arrived, but the same turn closed the coverage "
                                + "set. Asking the model to submit the assessment again.");
                        history.add(new UserMessage(AgentPrompts.conclusionReprompt()));
                        continue;
                    }
                    if (++coverageReprompts > properties.maxCoverageReprompts()) {
                        logExhausted(context);
                        break;
                    }
                    history.add(new UserMessage(AgentPrompts.coverageReprompt(stillMissing)));
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
                        missing.stream().map(RiskAgentLoop::displayName).toList());
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
                // Coverage is complete at this point - the block above returned otherwise - so a
                // conclusion the model wrote as prose instead of calling the tool is a formatting
                // failure, not a missing analysis. Observed live: the model printed the exact JSON
                // the tool wanted inside a paragraph, and the loop paid two more round trips of an
                // 8m36s run to get the same answer through the tool. Accept it, and record in the
                // trace that it arrived as prose.
                FinalAssessment written = FinalAssessmentParser.parse(text, jsonMapper);
                if (written != null) {
                    context.conclude(written);
                    context.trace().proseFinal(written.riskLevel().name(), written.summary());
                    log.info("Analysis {}: the model wrote its final assessment as prose; every rule "
                            + "already had a verdict, so it was accepted without another round trip",
                            context.assessmentId());
                    break;
                }
                if (++conclusionReprompts > properties.maxCoverageReprompts()) {
                    context.trace().reprompt("The model stopped without submitting an assessment; "
                            + "every rule has a verdict, so the summary is generated from the "
                            + "verdicts themselves.");
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
        log.warn("Analysis {}: coverage reprompt budget exhausted with {} rule(s) still unjudged; the "
                        + "run will be recorded as failed", context.assessmentId(),
                context.missingRules().size());
    }

    /**
     * Turns one round of executed tool calls into {@code tool_call} trace steps.
     *
     * <p>Each step takes the note the tool left for it - the rule it judged and the verdict, the
     * transaction it opened, the query it ran - so a transcript of twelve rules reads as twelve
     * named verdicts instead of two dozen rows all labelled "Submit rule verdict".
     */
    private void recordToolCalls(AgentRunContext context, RiskAgentTools tools,
            AssistantMessage assistant, List<Message> conversation) {
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
                    ms == null ? 0L : ms, tools.takeNote(call.name()));
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
    // Settling the run
    // ------------------------------------------------------------------

    /**
     * Turns the agent's verdicts into the run's result: one {@link RuleOutcome} per rule it judged,
     * the total score, the band that is actually recorded, and the list of rules it never judged.
     *
     * <p>Nothing is invented here. A rule with no verdict produces no outcome and therefore no
     * {@code risk_assessments} row - writing "not triggered, 0.00" for a rule nobody looked at would
     * be a false record - and it is instead named in {@link AgentRunResult#unjudgedRules()}, which is
     * what makes the run fail.
     *
     * <p>Nothing is re-judged here either. Each outcome carries the verdict PostgreSQL gave, the ids
     * its query returned and the query itself; this method only sums, bands and applies the one
     * discretionary move the agent has - an escalation above the mechanical band, taken only when it
     * is upwards and justified.
     *
     * <p>Called after every loop, and on its own when the conversation broke part-way.
     */
    public AgentRunResult settle(AgentRunContext context, int steps, long durationMs) {
        List<RuleOutcome> outcomes = new ArrayList<>(context.ruleCount());
        List<UnjudgedRule> unjudged = new ArrayList<>();
        BigDecimal total = ZERO;

        for (RiskRule rule : context.rules()) {
            UUID ruleId = rule.getRuleId();
            AgentRuleVerdict verdict = context.verdict(ruleId);
            if (verdict == null) {
                unjudged.add(new UnjudgedRule(ruleId, displayName(rule)));
                continue;
            }
            List<UUID> inScope = context.inScopeTransactionIds(ruleId);
            // Every matched id came out of the query and was checked against this rule's scope by
            // evaluate_rule before the verdict was recorded, so the rows written from here cannot
            // name a transaction the rule does not apply to.
            List<UUID> matched = verdict.transactionIds();
            BigDecimal score = verdict.triggered() ? scale(verdict.score()) : ZERO;
            total = total.add(score);
            outcomes.add(new RuleOutcome(
                    ruleId,
                    rule.getRuleName(),
                    rule.getAppliesTo(),
                    scale(rule.getWeight()),
                    verdict.triggered(),
                    score,
                    RuleVerdictSource.SQL_DERIVED,
                    inScope.size(),
                    verdict.matchedCount(),
                    matched,
                    inScope,
                    verdict.explanation(),
                    verdict.sql()));
        }

        if (!unjudged.isEmpty()) {
            context.trace().coverageFailed(context.ruleCount(),
                    unjudged.stream().map(rule -> rule.ruleId().toString()).toList(),
                    unjudged.stream().map(UnjudgedRule::ruleName).toList());
        }

        // The floor: the summed weights of the rules whose queries returned rows, banded. Every term
        // of it came out of a query result, which is why the agent may only argue it upwards.
        RiskLevel mechanical = RiskLevel.forScore(total);
        FinalAssessment conclusion = context.finalAssessment();
        // An escalation is honoured only when it is one - higher than the floor and justified. The
        // tool already refuses anything else, but the prose path has no tool to refuse it, so the
        // decision is taken here as well and both paths land on the same rule.
        boolean escalated = conclusion != null && conclusion.escalates(mechanical);
        RiskLevel recorded = conclusion == null ? mechanical : conclusion.bandOver(mechanical);
        String justification = escalated ? conclusion.escalationJustification() : null;
        if (conclusion != null && conclusion.riskLevel() != null && !escalated
                && conclusion.riskLevel() != mechanical) {
            log.info("Analysis {}: the agent proposed {} but the rule scores band to {}; {} was "
                            + "recorded", context.assessmentId(), conclusion.riskLevel(), mechanical,
                    mechanical);
        }
        String summary = conclusion != null && conclusion.summary() != null
                ? conclusion.summary()
                : fallbackSummary(context, outcomes, unjudged, total, mechanical);
        String recommendations = conclusion != null && conclusion.recommendations() != null
                ? conclusion.recommendations()
                : fallbackRecommendations(outcomes, unjudged, recorded);

        context.trace().finalStep(recorded.name(), mechanical.name(), justification, summary, total,
                context.ruleCount(), unjudged.isEmpty());

        return new AgentRunResult(
                context.assessmentId(),
                recorded,
                mechanical,
                conclusion == null ? null : conclusion.riskLevel(),
                justification,
                total,
                summary,
                recommendations,
                outcomes,
                context.ruleCount(),
                unjudged,
                steps,
                modelId(),
                durationMs);
    }

    /**
     * Narrative used when the model never wrote one - because it ran out of turns, or because the
     * conversation broke after it had already judged rules. It reports what the verdicts say and,
     * when rules were left unjudged, says so first: a summary that read like a completed review
     * would be the exact failure this design refuses to make.
     */
    private static String fallbackSummary(AgentRunContext context, List<RuleOutcome> outcomes,
            List<UnjudgedRule> unjudged, BigDecimal total, RiskLevel banded) {
        StringBuilder summary = new StringBuilder();
        if (!unjudged.isEmpty()) {
            StringJoiner names = new StringJoiner(", ");
            unjudged.forEach(rule -> names.add("\"" + rule.ruleName() + "\""));
            summary.append("INCOMPLETE ANALYSIS: ").append(unjudged.size()).append(" of ")
                    .append(context.ruleCount())
                    .append(" applicable rule(s) never received a verdict (").append(names)
                    .append("), so this run was recorded as failed and must be repeated. ");
        }
        summary.append("The AI analyst did not submit a written assessment, so this summary was "
                + "generated from the ").append(outcomes.size()).append(" rule verdict(s) it did "
                + "submit. ");
        List<RuleOutcome> triggered = outcomes.stream().filter(RuleOutcome::triggered).toList();
        if (outcomes.isEmpty()) {
            summary.append("No rule was judged at all, so there is no finding to report.");
        } else if (triggered.isEmpty()) {
            summary.append("None of the rules judged was found to be breached.");
        } else {
            StringJoiner names = new StringJoiner(", ");
            triggered.forEach(outcome -> names.add(outcome.ruleName()));
            summary.append(triggered.size()).append(" of ").append(outcomes.size())
                    .append(" judged rules were found to be breached: ").append(names).append('.');
        }
        summary.append(" Total score ").append(total.toPlainString()).append(", banded ")
                .append(banded).append(", summed from the weights of the rules whose queries "
                        + "returned rows.");
        return summary.toString();
    }

    private static String fallbackRecommendations(List<RuleOutcome> outcomes,
            List<UnjudgedRule> unjudged, RiskLevel banded) {
        String rerun = unjudged.isEmpty() ? "" : "Re-run this analysis: " + unjudged.size()
                + " rule(s) were never judged, so the review is not complete.\n";
        if (outcomes.stream().noneMatch(RuleOutcome::triggered)) {
            if (unjudged.isEmpty()) {
                return rerun + "No action required beyond the standard periodic review.";
            }
            // "No breach was found" would be a reassurance the run has not earned when nothing at
            // all was judged; the only honest line then is that there is nothing to report on.
            return rerun + (outcomes.isEmpty()
                    ? "Nothing was judged, so this run says nothing about the customer either way."
                    : "No breach was found among the " + outcomes.size()
                            + " rule(s) that were judged, but the review is not complete.");
        }
        return rerun + switch (banded) {
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

    /** The administrator-authored rule name, safe to show in a trace or a prompt. */
    private static String displayName(RiskRule rule) {
        String name = PromptSafety.inline(rule.getRuleName());
        return name == null ? "(unnamed rule)" : name;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
