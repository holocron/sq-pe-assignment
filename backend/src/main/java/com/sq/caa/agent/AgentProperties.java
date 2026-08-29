package com.sq.caa.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning of the ReAct risk agent, bound from {@code caa.agent.*}.
 *
 * @param maxSteps             hard ceiling on model turns in one run. The loop counts one step per
 *                             {@code ChatModel.call}, so this bounds both latency and spend even if
 *                             the model never converges.
 * @param maxCoverageReprompts how often the loop may refuse to let the model conclude while rules
 *                             remain unjudged. Once exhausted the loop stops; if rules are still
 *                             unjudged at that point the run is recorded as FAILED rather than
 *                             completed, so this bounds how long a stubborn model may argue, never
 *                             what may be reported as a finished analysis.
 * @param maxTokens            completion budget per turn. The chat model emits reasoning content,
 *                             so anything below ~2048 comes back with an empty message.
 * @param temperature          sampling temperature; low, because this is an audit task.
 * @param contextTokens        size of the model server's context window, prompt and completion
 *                             together. The advertised window of a model is not necessarily the one
 *                             its backend was started with - this is the number the backend
 *                             actually enforces, and the transcript is compacted to fit it.
 * @param contextReserveTokens headroom kept free on top of {@code maxTokens} to absorb the
 *                             difference between the estimated and the real token count.
 * @param keepRecentMessages   how many trailing messages stay verbatim when the transcript is
 *                             compacted; older tool results are replaced by a placeholder.
 * @param model                model id override. When blank the model configured on the injected
 *                             {@code ChatModel} is used.
 * @param concurrentRuns       how many analyses may execute at the same time.
 * @param queueCapacity        how many analyses may wait; beyond that a request is rejected with
 *                             503 rather than queued forever.
 * @param streamTimeout        how long an idle SSE subscription is held open.
 * @param requestTimeout       how long one {@code ChatModel.call} may take before the HTTP client
 *                             abandons it. This has to be set on the options object we build:
 *                             {@code OpenAiChatOptions.builder()} bakes in a 60-second timeout and
 *                             three retries, and those win over {@code spring.ai.openai.timeout}
 *                             for any call that supplies its own options - which every call here
 *                             does, because tool callbacks have to be attached. Left at the builder
 *                             default, a turn of a local reasoning model that needs more than a
 *                             minute is cut off and retried until the run fails.
 * @param transactionPageSize  default page size of {@code list_transactions}.
 */
@ConfigurationProperties(prefix = "caa.agent")
public record AgentProperties(
        @DefaultValue("40") int maxSteps,
        @DefaultValue("3") int maxCoverageReprompts,
        @DefaultValue("4096") int maxTokens,
        @DefaultValue("0.1") double temperature,
        @DefaultValue("32768") int contextTokens,
        @DefaultValue("1536") int contextReserveTokens,
        @DefaultValue("10") int keepRecentMessages,
        @DefaultValue("") String model,
        @DefaultValue("2") int concurrentRuns,
        @DefaultValue("16") int queueCapacity,
        @DefaultValue("30m") Duration streamTimeout,
        @DefaultValue("10m") Duration requestTimeout,
        @DefaultValue("25") int transactionPageSize) {

    public AgentProperties {
        maxSteps = clamp(maxSteps, 1, 400);
        maxCoverageReprompts = clamp(maxCoverageReprompts, 0, 50);
        maxTokens = clamp(maxTokens, 512, 131072);
        temperature = temperature < 0 ? 0 : Math.min(temperature, 2);
        contextTokens = clamp(contextTokens, 4096, 1_000_000);
        contextReserveTokens = clamp(contextReserveTokens, 0, 65536);
        keepRecentMessages = clamp(keepRecentMessages, 2, 200);
        concurrentRuns = clamp(concurrentRuns, 1, 16);
        queueCapacity = clamp(queueCapacity, 1, 1000);
        transactionPageSize = clamp(transactionPageSize, 1, 100);
        streamTimeout = streamTimeout == null ? Duration.ofMinutes(30) : streamTimeout;
        requestTimeout = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                ? Duration.ofMinutes(10)
                : requestTimeout;
        model = model == null ? "" : model.trim();
    }

    /**
     * Tokens the prompt - transcript plus tool schemas - may occupy: the context window less the
     * completion budget and the safety reserve. Never below a quarter of the window, so a
     * misconfigured reserve cannot starve the conversation.
     */
    public int promptBudgetTokens() {
        return Math.max(contextTokens / 4, contextTokens - maxTokens - contextReserveTokens);
    }

    /** The configured model id, or {@code fallback} when none was set. */
    public String modelOr(String fallback) {
        return model.isEmpty() ? fallback : model;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
