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
import java.util.Queue;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The orchestrator of an analysis run: one rule subagent per applicable rule, then one closing
 * conversation that writes the assessment over the verdict table.
 *
 * <h2>Why subagents</h2>
 * <p>The run used to be a single 40-turn conversation that investigated, judged every rule and then
 * summarised. One conversation meant one ever-growing transcript (the compactor's whole existence),
 * one shared budget for twelve rules, and a model that had to keep the whole checklist in its head.
 * Now each applicable rule gets its own <b>rule subagent</b>: a fresh ReAct mini-loop with the full
 * read-only tool set plus a verdict tool scoped to that one rule
 * ({@link RiskAgentTools#forRule}). It must end by submitting a verdict through
 * {@code evaluate_rule}; a subagent that exhausts its step budget without one reports failure, and
 * the orchestrator retries it once on a fresh conversation.
 *
 * <p>Spring AI 2.x does not execute tools inside {@code ChatModel.call} - tool execution moved to a
 * ChatClient advisor - so calling the model directly hands the tool calls back unexecuted and the
 * loops here drive them. That is what makes the gate possible: nothing between the model and the
 * database gets to decide when the analysis is over except this code.
 *
 * <h2>The rule-coverage guarantee, unchanged</h2>
 * <p>A rule condition is prose; the subagent reads it, writes the SELECT that answers it and
 * {@code evaluate_rule} runs that query. There is no engine left to quietly close a rule, so
 * coverage is guaranteed by refusing to call such a run finished:
 * <ol>
 *   <li>The coverage set is fixed in the {@link AgentRunContext} before the first turn.</li>
 *   <li>{@code evaluate_rule} is the only way into the evaluated set, and only a query that actually
 *       executed gets a rule in. A rejected or failed query records nothing.</li>
 *   <li>A rule whose subagent failed twice never received a verdict; {@link #settle} then builds one
 *       outcome per rule that was judged and {@link #execute} throws {@link AgentRunFailedException}
 *       carrying that partial result and an {@link IncompleteRuleCoverageException} naming the
 *       unjudged rules: the caller persists the run as {@code FAILED}, keeping every verdict that
 *       was obtained. A partial analysis is never reported as {@code COMPLETED}.</li>
 * </ol>
 *
 * <h2>Which band is recorded</h2>
 * <p>{@link #settle} sums the per-rule scores - each one a rule's weight because that rule's query
 * returned rows, or zero because it did not - and bands the total. That mechanical band is the floor.
 * The closing conversation's own proposal is honoured only when it is <em>higher</em> and comes with
 * a justification, which is recorded beside it; anything lower is discarded here exactly as the tool
 * refuses it, so a narrative can never talk a scored breach down into a clean review.
 *
 * <p>The cost of this design is stated rather than hidden: the model writes the query afresh each
 * run, so two runs of the same customer can differ. What does not vary is what happens once a query
 * has run - the verdict is its row count and the score is the rule's weight, never an estimate - and
 * the coverage claim: a {@code COMPLETED} run has a verdict for every applicable rule, or it is not
 * {@code COMPLETED}.
 *
 * <h2>Concurrency</h2>
 * <p>Subagents run on a per-run pool bounded by {@code caa.agent.subagent-parallelism}; the pool is
 * shut down before {@link #execute} returns, so it never outlives the run. Shared state is either
 * immutable (the coverage set), per-rule keyed (verdicts, SQL attempt budgets, scopes in
 * {@link AgentRunContext}) or per-subagent (each subagent gets its own {@link RiskAgentTools}
 * instance, its own conversation and its own {@link ConversationCompactor}). Cancellation is polled
 * between steps of every subagent and while the orchestrator waits on the fan-out, and lands at the
 * next step boundary.
 */
@Component
public class RiskAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(RiskAgentLoop.class);

    /** Used only if neither the properties nor the chat model name a model. */
    private static final String FALLBACK_MODEL = "gpt-oss-120b-GGUF";

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /** How often one conversation may replay a turn after the server refused the prompt as too large. */
    private static final int MAX_CONTEXT_RETRIES = 3;

    /** How often a failed rule subagent is re-run: once, on a fresh conversation. */
    private static final int MAX_SUBAGENT_ATTEMPTS = 2;

    /** Tools a rule subagent may call: the full read-only set plus its own verdict tool. */
    private static final Set<String> SUBAGENT_TOOLS = Set.of(
            RiskAgentTools.GET_CUSTOMER_PROFILE,
            RiskAgentTools.GET_CUSTOMER_ACTIVITY_SUMMARY,
            RiskAgentTools.LIST_TRANSACTIONS,
            RiskAgentTools.GET_TRANSACTION_DETAILS,
            RiskAgentTools.SEARCH_POLICY_KNOWLEDGE,
            RiskAgentTools.EVALUATE_RULE);

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final JsonMapper jsonMapper;
    private final AgentProperties properties;
    private final AgentTracer tracer;

    /** Without the verbose file trace; for tests that do not exercise {@link AgentTracer}. */
    public RiskAgentLoop(ChatModel chatModel, ToolCallingManager toolCallingManager,
            JsonMapper jsonMapper, AgentProperties properties) {
        this(chatModel, toolCallingManager, jsonMapper, properties, AgentTracer.noop());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RiskAgentLoop(ChatModel chatModel, ToolCallingManager toolCallingManager,
            JsonMapper jsonMapper, AgentProperties properties, AgentTracer tracer) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.tracer = tracer == null ? AgentTracer.noop() : tracer;
    }

    /** Model id this loop will drive, recorded on the run before it starts. */
    public String modelId() {
        ChatOptions options = chatModel.getOptions();
        if (options == null || options.getModel() == null || options.getModel().isBlank()) {
            // getOptions() is a default interface method some wrappers leave unimplemented;
            // getDefaultOptions() is the one OpenAiChatModel reliably fills.
            options = chatModel.getDefaultOptions();
        }
        String configured = options == null ? null : options.getModel();
        return properties.modelOr(configured == null || configured.isBlank() ? FALLBACK_MODEL : configured);
    }

    /**
     * Runs the whole analysis: fans the applicable rules out to their subagents, retries the failed
     * ones once, then has the closing conversation write the assessment over the verdict table.
     * Blocking and slow by nature - the caller must already be off the request thread.
     *
     * @return the settled run, only ever with every applicable rule judged
     * @throws AgentRunFailedException when a subagent conversation broke, or when the fan-out ended
     *                                 with rules still unjudged; {@link AgentRunFailedException#result()}
     *                                 carries everything the subagents did establish
     */
    public AgentRunResult execute(AgentRunContext context, RiskAgentTools tools) {
        long startedAt = System.currentTimeMillis();
        String model = modelId();
        context.trace().started(model, context.customer().getFullName(), context.ruleCount());
        tracer.runStarted(context.assessmentId(), context.customer().getFullName(), model,
                context.ruleCount());
        try {
            fanOut(context, tools, model);
            if (context.coverageComplete()) {
                // The summary conversation runs only over a full verdict table; a run with unjudged
                // rules skips it and fails below, exactly as the old loop did.
                concludeWithSummary(context, tools, model);
            }
        } catch (CancellationSignal signal) {
            // Cancelled at the user's request: settle from the verdicts already obtained, exactly as
            // a broken run is settled - a cancelled run keeps its work and is persisted CANCELLED,
            // never COMPLETED and never silently dropped.
            context.trace().cancelled();
            AgentRunResult partial = settle(context, context.stepsTaken(),
                    System.currentTimeMillis() - startedAt);
            tracer.note(context.assessmentId(), "CANCELLED",
                    "The analysis was cancelled at the user's request after " + context.stepsTaken()
                            + " step(s); the verdicts obtained so far are kept.");
            throw new AgentRunCancelledException(partial);
        } catch (RuntimeException e) {
            // A conversation died - but everything the subagents had already submitted is still in
            // the context, so the run is settled from it rather than thrown away. The caller marks
            // the run FAILED with this cause and keeps the verdicts that were obtained.
            AgentRunResult partial = settle(context, context.stepsTaken(),
                    System.currentTimeMillis() - startedAt);
            tracer.note(context.assessmentId(), "RUN FAILED",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            throw new AgentRunFailedException(partial, e);
        }
        AgentRunResult result = settle(context, context.stepsTaken(),
                System.currentTimeMillis() - startedAt);
        if (!result.coverageComplete()) {
            // The gate's last line. Every subagent - first run and retry - is done and rules are
            // still unjudged; there is no backfill to close them, so this run must not be reported
            // as a finished analysis.
            log.warn("Analysis {}: {} of {} rule(s) never received a verdict; the run is recorded as "
                            + "FAILED with the {} verdict(s) it did obtain", context.assessmentId(),
                    result.unjudgedRules().size(), result.rulesTotal(), result.rulesJudged());
            throw new AgentRunFailedException(result, new IncompleteRuleCoverageException(
                    result.rulesTotal(), result.unjudgedRules(), result.unjudgedRuleNames()));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // The fan-out
    // ------------------------------------------------------------------

    /**
     * Runs every applicable rule's subagent on a bounded per-run pool and waits for all of them.
     *
     * <p>The wait polls in slices rather than blocking on one future at a time so a cancellation
     * lands promptly - within a slice of being requested - instead of after the slowest subagent.
     * The pool is shut down on the way out, cancelled or not: it must never outlive the run.
     */
    private void fanOut(AgentRunContext context, RiskAgentTools tools, String model) {
        List<RiskRule> rules = context.rules();
        if (rules.isEmpty()) {
            return;
        }
        int parallelism = Math.min(properties.subagentParallelism(), rules.size());
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, new SubagentThreadFactory());
        // Worker ids identify the pool slot in the trace; they are recycled as subagents finish.
        Queue<Integer> freeWorkers = new ConcurrentLinkedQueue<>();
        for (int slot = 1; slot <= parallelism; slot++) {
            freeWorkers.add(slot);
        }
        try {
            List<Future<?>> futures = new ArrayList<>(rules.size());
            for (RiskRule rule : rules) {
                futures.add(pool.submit(() -> runWithRetry(context, tools, rule, model, freeWorkers)));
            }
            for (Future<?> future : futures) {
                await(context, future, futures);
            }
        } finally {
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("Analysis {}: subagent pool did not stop within 10 seconds",
                            context.assessmentId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Waits on one subagent task, aborting the whole fan-out promptly when cancellation lands. */
    private void await(AgentRunContext context, Future<?> future, List<Future<?>> futures) {
        while (true) {
            if (context.trace().isCancellationRequested()) {
                futures.forEach(task -> task.cancel(true));
                throw new CancellationSignal();
            }
            try {
                future.get(100, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException e) {
                // Not done yet; poll again.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationSignal();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof CancellationSignal signal) {
                    throw signal;
                }
                if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(e.getCause());
            }
        }
    }

    /**
     * One rule's subagent, with the orchestrator's retry policy around it: a subagent that ends
     * without a verdict is re-run once, on a fresh conversation, and the retry is announced in both
     * traces.
     */
    private void runWithRetry(AgentRunContext context, RiskAgentTools tools, RiskRule rule,
            String model, Queue<Integer> freeWorkers) {
        Integer worker = freeWorkers.poll();
        if (worker == null) {
            worker = 0; // cannot happen - the pool has one slot per id - but never break a run on it
        }
        try {
            for (int attempt = 1; attempt <= MAX_SUBAGENT_ATTEMPTS; attempt++) {
                SubagentOutcome outcome = runSubagent(context, tools, rule, worker, attempt, model);
                if (outcome.judged()) {
                    return;
                }
                if (context.trace().isCancellationRequested()) {
                    throw new CancellationSignal();
                }
                if (attempt < MAX_SUBAGENT_ATTEMPTS) {
                    log.warn("Analysis {}: the subagent for rule \"{}\" ended without a verdict; "
                                    + "retrying once with a fresh conversation",
                            context.assessmentId(), displayName(rule));
                    context.trace().reprompt("The subagent for rule \"" + displayName(rule)
                            + "\" exhausted its step budget without submitting a verdict; the "
                            + "orchestrator is retrying it once with a fresh conversation.");
                    tracer.note(context.assessmentId(), "SUBAGENT RETRY",
                            "Rule \"" + displayName(rule) + "\": first subagent failed without a "
                                    + "verdict; retrying with a fresh conversation.");
                }
            }
        } finally {
            freeWorkers.offer(worker);
        }
    }

    // ------------------------------------------------------------------
    // One rule subagent's mini-loop
    // ------------------------------------------------------------------

    private record SubagentOutcome(boolean judged) {
    }

    /**
     * The ReAct mini-loop of one rule subagent.
     *
     * <p>Fresh conversation, own step budget ({@code caa.agent.subagent-max-steps}), the full
     * read-only tool set plus a verdict tool scoped to {@code rule}. The loop ends the moment the
     * rule has a recorded verdict; exhausting the budget without one is a failure the orchestrator
     * retries once. Cancellation is polled between turns and aborts immediately.
     */
    private SubagentOutcome runSubagent(AgentRunContext context, RiskAgentTools sharedTools,
            RiskRule rule, int worker, int attempt, String model) {
        long startedAt = System.currentTimeMillis();
        String ruleName = displayName(rule);
        context.trace().subagentStart(rule.getRuleId(), ruleName, worker, attempt);
        tracer.subagentStarted(context.assessmentId(), ruleName, worker, attempt);

        int steps = 0;
        int contextRetries = 0;
        int verdictReprompts = 0;
        RiskAgentTools tools = sharedTools.forRule(rule);
        List<ToolCallback> callbacks = callbacksFor(tools, SUBAGENT_TOOLS);
        OpenAiChatOptions options = options(callbacks, model);
        ConversationCompactor compactor =
                new ConversationCompactor(properties.promptBudgetTokens(), properties.keepRecentMessages());

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(AgentPrompts.subagentSystem()));
        history.add(new UserMessage(AgentPrompts.subagentTask(context.customer(), rule)));

        while (steps < properties.subagentMaxSteps()) {
            // The cancellation flag is polled between turns, so a cancel lands at the next step
            // boundary rather than inside a model call or half-way through a tool batch.
            if (context.trace().isCancellationRequested()) {
                log.info("Analysis {}: cancellation requested; subagent for \"{}\" aborts after {} "
                        + "step(s)", context.assessmentId(), ruleName, steps);
                throw new CancellationSignal();
            }
            // The schemas ride along with every request, so they are part of the budget, and the
            // budget is re-derived each turn because calibration keeps moving the estimate.
            history = fitToContext(context, history, compactor.estimateTools(callbacks), compactor);
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
                // tighten the estimator and replay the turn instead of failing the subagent.
                if (isContextOverflow(e) && contextRetries++ < MAX_CONTEXT_RETRIES
                        && compactor.tighten()) {
                    log.warn("Analysis {}: the model server rejected a subagent prompt as too large; "
                            + "compacting harder and retrying the turn", context.assessmentId(), e);
                    context.trace().reprompt("The model server refused the prompt as too large for "
                            + "its context window; the transcript was compacted further and the turn "
                            + "retried.");
                    tracer.note(context.assessmentId(), "CONTEXT RETRY",
                            "The prompt was refused as too large; compacted further and retried.");
                    continue;
                }
                // The conversation broke. The subagent reports failure; the retry policy is the
                // orchestrator's, not this loop's.
                log.warn("Analysis {}: the subagent for rule \"{}\" died after {} step(s)",
                        context.assessmentId(), ruleName, steps, e);
                break;
            }
            calibrate(context, estimated, response, compactor);

            AssistantMessage assistant = response.getResult() == null
                    ? null
                    : response.getResult().getOutput();
            String text = assistant == null ? null : assistant.getText();

            if (response.hasToolCalls()) {
                if (hasText(text)) {
                    context.trace().assistant(text);
                    tracer.assistant(context.assessmentId(), text);
                }
                ToolExecutionResult execution = toolCallingManager.executeToolCalls(prompt, response);
                List<Message> updated = new ArrayList<>(execution.conversationHistory());
                recordToolCalls(context, tools, assistant, updated, ruleName);
                history = updated;
                if (context.isEvaluated(rule.getRuleId())) {
                    break;
                }
                continue;
            }

            // No tool calls. Without a recorded verdict the subagent is not finished - the verdict
            // is the whole point of its existence - so it is told so, a bounded number of times.
            if (hasText(text)) {
                context.trace().assistant(text);
                tracer.assistant(context.assessmentId(), text);
            }
            if (context.isEvaluated(rule.getRuleId())) {
                break;
            }
            if (++verdictReprompts > properties.maxCoverageReprompts()) {
                break;
            }
            if (assistant != null) {
                history.add(assistant);
            }
            history.add(new UserMessage(hasText(text)
                    ? AgentPrompts.subagentVerdictReprompt(rule)
                    : AgentPrompts.emptyTurnReprompt()));
        }

        AgentRuleVerdict verdict = context.verdict(rule.getRuleId());
        long durationMs = System.currentTimeMillis() - startedAt;
        String state = verdict == null ? "failed" : verdict.triggered() ? "triggered" : "not_triggered";
        context.trace().subagentEnd(rule.getRuleId(), ruleName, worker, attempt, state,
                verdict == null ? null : verdict.score(), steps, durationMs);
        tracer.subagentEnded(context.assessmentId(), ruleName, worker, attempt, state,
                verdict == null ? null : verdict.score().toPlainString(), steps, durationMs);
        return new SubagentOutcome(verdict != null);
    }

    // ------------------------------------------------------------------
    // The closing summary conversation
    // ------------------------------------------------------------------

    /**
     * The orchestrator's one conversation: given the full verdict table, write the summary, the
     * recommendations and the risk level - through {@code submit_final_assessment}, or as prose,
     * accepted under the same rule as always (the mechanical band is the floor).
     *
     * <p>Runs only at full coverage. If the model never concludes, {@link #settle} generates the
     * narrative from the verdicts themselves, exactly as before.
     */
    private void concludeWithSummary(AgentRunContext context, RiskAgentTools tools, String model) {
        List<ToolCallback> callbacks =
                callbacksFor(tools, Set.of(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT));
        OpenAiChatOptions options = options(callbacks, model);
        ConversationCompactor compactor =
                new ConversationCompactor(properties.promptBudgetTokens(), properties.keepRecentMessages());

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(AgentPrompts.summarySystem()));
        history.add(new UserMessage(AgentPrompts.summaryTask(context.customer(), context.rules(),
                context::verdict, context.totalScore(), context.mechanicalRiskLevel())));

        int maxTurns = Math.min(properties.maxSteps(), 2 + properties.maxCoverageReprompts());
        int steps = 0;
        int contextRetries = 0;
        int conclusionReprompts = 0;
        while (steps < maxTurns) {
            if (context.trace().isCancellationRequested()) {
                throw new CancellationSignal();
            }
            history = fitToContext(context, history, compactor.estimateTools(callbacks), compactor);
            Prompt prompt = new Prompt(history, options);
            int estimated = compactor.estimate(history) + compactor.estimateTools(callbacks);
            steps++;
            context.recordStep();
            ChatResponse response;
            try {
                response = chatModel.call(prompt);
            } catch (RuntimeException e) {
                if (isContextOverflow(e) && contextRetries++ < MAX_CONTEXT_RETRIES
                        && compactor.tighten()) {
                    context.trace().reprompt("The model server refused the prompt as too large for "
                            + "its context window; the transcript was compacted further and the turn "
                            + "retried.");
                    tracer.note(context.assessmentId(), "CONTEXT RETRY",
                            "The prompt was refused as too large; compacted further and retried.");
                    continue;
                }
                throw e;
            }
            calibrate(context, estimated, response, compactor);

            AssistantMessage assistant = response.getResult() == null
                    ? null
                    : response.getResult().getOutput();
            String text = assistant == null ? null : assistant.getText();

            if (response.hasToolCalls()) {
                if (hasText(text)) {
                    context.trace().assistant(text);
                    tracer.assistant(context.assessmentId(), text);
                }
                ToolExecutionResult execution = toolCallingManager.executeToolCalls(prompt, response);
                List<Message> updated = new ArrayList<>(execution.conversationHistory());
                recordToolCalls(context, tools, assistant, updated, null);
                history = updated;
                if (context.consumeConclusionRejected()) {
                    // Full coverage is a precondition of this phase, so the gate should never fire
                    // here; if it does, name what is missing and let the run fail honestly below.
                    context.trace().reprompt("The final assessment was rejected with rules still "
                            + "open; the run will be settled from the verdicts on record.");
                    return;
                }
                if (context.isConcluded()) {
                    return;
                }
                continue;
            }

            // No tool calls: the model considers itself finished. A parseable written assessment is
            // accepted exactly as in the old loop - coverage is complete, which is the only
            // condition under which this phase runs at all.
            if (hasText(text)) {
                context.trace().assistant(text);
                tracer.assistant(context.assessmentId(), text);
            }
            FinalAssessment written = FinalAssessmentParser.parse(text, jsonMapper);
            if (written != null) {
                context.conclude(written);
                context.trace().proseFinal(written.riskLevel().name(), written.summary());
                log.info("Analysis {}: the closing assessment arrived as prose and was accepted",
                        context.assessmentId());
                return;
            }
            if (++conclusionReprompts > properties.maxCoverageReprompts()) {
                context.trace().reprompt("The model stopped without submitting an assessment; the "
                        + "summary is generated from the verdicts themselves.");
                return;
            }
            context.trace().reprompt("No assessment was submitted; asking the model to conclude.");
            if (assistant != null) {
                history.add(assistant);
            }
            history.add(new UserMessage(hasText(text)
                    ? AgentPrompts.conclusionReprompt()
                    : AgentPrompts.emptyTurnReprompt()));
        }
        context.trace().reprompt("The closing conversation exhausted its turns without an "
                + "assessment; the summary is generated from the verdicts themselves.");
    }

    // ------------------------------------------------------------------
    // Shared loop machinery
    // ------------------------------------------------------------------

    /** The tool callbacks of {@code tools} restricted to the named tools. */
    private static List<ToolCallback> callbacksFor(RiskAgentTools tools, Set<String> names) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ToolCallback callback : ToolCallbacks.from(tools)) {
            if (names.contains(callback.getToolDefinition().name())) {
                callbacks.add(callback);
            }
        }
        return List.copyOf(callbacks);
    }

    private OpenAiChatOptions options(List<ToolCallback> callbacks, String model) {
        return OpenAiChatOptions.builder()
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
    }

    /**
     * Compacts the transcript when it is about to overflow the model server's context window.
     *
     * <p>Compaction is per conversation - each subagent owns its {@link ConversationCompactor} - and
     * is logged and traced, because a reviewer reading the transcript must be able to see that the
     * model was working from an abridged history.
     */
    private List<Message> fitToContext(AgentRunContext context, List<Message> history, int toolTokens,
            ConversationCompactor compactor) {
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
    private void calibrate(AgentRunContext context, int estimated, ChatResponse response,
            ConversationCompactor compactor) {
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

    /**
     * Turns one round of executed tool calls into {@code tool_call} trace steps.
     *
     * <p>Each step takes the note the tool left for it - the rule it judged and the verdict, the
     * transaction it opened, the query it ran - so a transcript of twelve rules reads as twelve
     * named verdicts instead of two dozen rows all labelled "Submit rule verdict". Calls made inside
     * a rule subagent are attributed to it ({@code ruleName} on the step, and the note/timing queues
     * are read from that subagent's own tool instance, never a shared one).
     */
    private void recordToolCalls(AgentRunContext context, RiskAgentTools tools,
            AssistantMessage assistant, List<Message> conversation, String ruleName) {
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
            Long ms = tools.takeTiming(call.name());
            JsonNode arguments = readJson(call.arguments());
            String result = results.get(call.id());
            context.trace().toolCall(call.name(), arguments, result,
                    ms == null ? 0L : ms, tools.takeNote(call.name()), ruleName);
            // The file trace gets the untruncated pair: pretty-printed arguments and the FULL
            // result, which is where the SQL behind every evaluate_rule verdict lives.
            tracer.toolCall(context.assessmentId(), call.name(), ruleName, pretty(arguments), result,
                    ms == null ? 0L : ms);
        }
    }

    private String pretty(JsonNode node) {
        if (node == null) {
            return "{}";
        }
        try {
            return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (RuntimeException e) {
            return node.toString();
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
     * Turns the subagents' verdicts into the run's result: one {@link RuleOutcome} per rule they
     * judged, the total score, the band that is actually recorded, and the list of rules never
     * judged.
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
     * <p>Called after every fan-out, and on its own when the run broke part-way.
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

        if (!unjudged.isEmpty() && !context.trace().isCancellationRequested()) {
            // Skipped for a cancelled run: "recorded as FAILED" would be a lie, and the cancelled
            // step already says why the checklist was left unfinished.
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
        tracer.finalAssessment(context.assessmentId(), recorded.name(), mechanical.name(),
                justification, total.toPlainString(), summary, recommendations, unjudged.isEmpty());

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

    /** Internal control flow: the user asked to cancel; unwind out of the fan-out or a subagent. */
    private static final class CancellationSignal extends RuntimeException {
        private CancellationSignal() {
            super(null, null, false, false);
        }
    }

    /** Daemon threads the per-run subagent pool executes on. */
    private static final class SubagentThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "risk-subagent-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
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
