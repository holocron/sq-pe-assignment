package com.sq.caa.agent;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

/**
 * Keeps the ReAct transcript inside the chat model's context window.
 *
 * <p>A full run is roughly thirty turns and every turn appends both an assistant message and the
 * tool results it asked for, so the transcript grows monotonically and eventually exceeds the
 * server-side context. That is not a theoretical limit: the run that motivated this class died at
 * turn 31 with {@code 500: Context size has been exceeded} after having evaluated every rule, and
 * lost the narrative it had already earned.
 *
 * <h2>How it compacts</h2>
 * It never <b>removes</b> a message. The OpenAI wire format requires every assistant tool call to be
 * answered by a tool message carrying the same {@code tool_call_id}; dropping a message breaks that
 * pairing and the request is rejected. Instead the oldest tool <em>results</em> are replaced by a
 * one-line placeholder, keeping ids and names intact, and long assistant prose is truncated. The
 * model can always re-call a tool - every tool of this agent is a pure read of a snapshot that was
 * frozen before the first turn, so a repeated call returns exactly the same answer.
 *
 * <h2>What is never touched</h2>
 * The system prompt and the opening task message (the rule checklist the coverage gate is graded
 * against), and the most recent {@code keepRecentMessages} messages, which are the working set the
 * model is actually reasoning over. Compaction only reaches into the recent window when eliding the
 * whole prefix was not enough, and even then it leaves the last exchange whole.
 */
final class ConversationCompactor {

    /** Stands in for a tool result that was dropped from the transcript. */
    static final String ELIDED_RESULT =
            "[earlier result omitted to stay inside the context window - call this tool again if you "
                    + "still need it; it returns the same answer]";

    /** Longest assistant message kept once compaction reaches it. */
    private static final int ASSISTANT_KEEP_CHARS = 400;

    /** Longest assistant message kept in the final, aggressive pass. */
    private static final int ASSISTANT_KEEP_CHARS_HARD = 120;

    private static final String TRUNCATION_MARK = " [...truncated]";

    /**
     * Starting characters-per-token ratio. Deliberately pessimistic - tool payloads are JSON full of
     * UUIDs and snake_case keys, which tokenises far worse than prose - so the estimate errs towards
     * compacting early rather than towards a 500. It is corrected from the server's own token counts
     * as soon as the first response comes back; see {@link #calibrate(int, Integer)}.
     */
    private static final double DEFAULT_CHARS_PER_TOKEN = 2.8;

    /** Floor for the calibrated ratio: below this the estimate would be absurd. */
    private static final double MIN_CHARS_PER_TOKEN = 1.5;

    /** Extra pessimism applied to a measured ratio, so calibration lands under the truth. */
    private static final double CALIBRATION_MARGIN = 0.97;

    /** Per-message wire overhead (role, delimiters, name fields). */
    private static final int MESSAGE_OVERHEAD_TOKENS = 8;

    private final int promptBudgetTokens;
    private final int keepRecentMessages;

    /**
     * Live characters-per-token ratio for this run's content. One compactor exists per loop, so the
     * calibration is per analysis and never leaks between runs.
     */
    private volatile double charsPerToken = DEFAULT_CHARS_PER_TOKEN;

    ConversationCompactor(int promptBudgetTokens, int keepRecentMessages) {
        this.promptBudgetTokens = Math.max(1024, promptBudgetTokens);
        this.keepRecentMessages = Math.max(2, keepRecentMessages);
    }

    /**
     * Corrects the estimator against the model server's own accounting.
     *
     * <p>A character-count heuristic cannot know how a given tokeniser will treat a payload, and
     * being wrong in the optimistic direction costs the whole run: the first attempt at this used a
     * fixed 3.2 characters per token, under-counted a transcript heavy in UUIDs and policy JSON by
     * about a fifth, and hit {@code Context size has been exceeded} anyway. So every response feeds
     * its real {@code prompt_tokens} back in. The ratio only ever moves in the pessimistic
     * direction within a run - a single cheap turn must not license an expensive one.
     *
     * @param estimatedTokens what {@link #estimateTokens(List)} predicted for the prompt just sent
     * @param actualPromptTokens what the server counted; ignored when absent or nonsensical
     */
    void calibrate(int estimatedTokens, Integer actualPromptTokens) {
        if (actualPromptTokens == null || actualPromptTokens <= 0 || estimatedTokens <= 0) {
            return;
        }
        if (actualPromptTokens <= estimatedTokens) {
            return;
        }
        double implied = charsPerToken * estimatedTokens / actualPromptTokens * CALIBRATION_MARGIN;
        charsPerToken = Math.max(MIN_CHARS_PER_TOKEN, Math.min(charsPerToken, implied));
    }

    /**
     * Forces the estimator to be markedly more pessimistic after the server has actually rejected a
     * prompt, so the retry is compacted hard rather than by the same margin that just failed.
     *
     * @return {@code true} if there was still room to tighten
     */
    boolean tighten() {
        double tightened = Math.max(MIN_CHARS_PER_TOKEN, charsPerToken * 0.7);
        if (tightened >= charsPerToken) {
            return false;
        }
        charsPerToken = tightened;
        return true;
    }

    /** The ratio currently in use; exposed for logging and tests. */
    double charsPerToken() {
        return charsPerToken;
    }

    /** Tokens this compactor will allow the prompt to reach, tool schemas included. */
    int promptBudgetTokens() {
        return promptBudgetTokens;
    }

    /**
     * Returns a transcript that fits the budget.
     *
     * @param history         the conversation so far; never mutated
     * @param overheadTokens  everything the request carries besides the messages, i.e. the tool
     *                        schemas
     * @return {@code history} itself when it already fits, otherwise a compacted copy
     */
    List<Message> compact(List<Message> history, int overheadTokens) {
        if (history == null || history.isEmpty()) {
            return history;
        }
        int total = overheadTokens + estimate(history);
        if (total <= promptBudgetTokens) {
            return history;
        }
        List<Message> out = new ArrayList<>(history);
        int floor = Math.min(2, out.size());
        int recentFrom = Math.max(floor, out.size() - keepRecentMessages);

        int last = Math.max(floor, out.size() - 2);
        // Oldest first, and only as far as the budget demands: elide the prefix, then the recent
        // window, and only if that still does not fit, cut the retained prose right back.
        total = shrinkRange(out, floor, recentFrom, total, ASSISTANT_KEEP_CHARS);
        if (total > promptBudgetTokens) {
            // The prefix alone was not enough. Reach into the recent window, but leave the last
            // exchange intact: the model needs at least its own last turn to stay coherent.
            total = shrinkRange(out, recentFrom, last, total, ASSISTANT_KEEP_CHARS);
        }
        if (total > promptBudgetTokens) {
            shrinkRange(out, floor, last, total, ASSISTANT_KEEP_CHARS_HARD);
        }
        return out;
    }

    private int shrinkRange(List<Message> messages, int from, int to, int startingTotal,
            int assistantKeepChars) {
        int total = startingTotal;
        for (int i = from; i < to && total > promptBudgetTokens; i++) {
            Message original = messages.get(i);
            Message shrunk = shrink(original, assistantKeepChars);
            if (shrunk == null) {
                continue;
            }
            total -= estimateTokens(original, charsPerToken) - estimateTokens(shrunk, charsPerToken);
            messages.set(i, shrunk);
        }
        return total;
    }

    /** A smaller version of {@code message}, or {@code null} when there is nothing to gain. */
    private static Message shrink(Message message, int assistantKeepChars) {
        if (message instanceof ToolResponseMessage toolResponses) {
            List<ToolResponseMessage.ToolResponse> elided = new ArrayList<>();
            boolean changed = false;
            for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
                String data = response.responseData();
                if (data != null && data.length() > ELIDED_RESULT.length()) {
                    elided.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(),
                            ELIDED_RESULT));
                    changed = true;
                } else {
                    elided.add(response);
                }
            }
            return changed ? ToolResponseMessage.builder().responses(elided).build() : null;
        }
        if (message instanceof AssistantMessage assistant) {
            String text = assistant.getText();
            if (text == null || text.length() <= assistantKeepChars) {
                return null;
            }
            return AssistantMessage.builder()
                    .content(text.substring(0, assistantKeepChars) + TRUNCATION_MARK)
                    .toolCalls(assistant.getToolCalls() == null ? List.of() : assistant.getToolCalls())
                    .build();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Estimation
    // ------------------------------------------------------------------

    /** Rough token cost of a whole transcript at the calibrated ratio. */
    int estimate(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimateTokens(message, charsPerToken);
        }
        return total;
    }

    /** Rough token cost of the tool schemas at the calibrated ratio. */
    int estimateTools(List<ToolCallback> callbacks) {
        return toolTokens(callbacks, charsPerToken);
    }

    /** Rough token cost of a whole transcript at the default ratio. */
    static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimateTokens(message, DEFAULT_CHARS_PER_TOKEN);
        }
        return total;
    }

    /** Rough token cost of one message, wire overhead included. */
    static int estimateTokens(Message message, double charsPerToken) {
        int chars = 0;
        if (message instanceof ToolResponseMessage toolResponses) {
            for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
                chars += length(response.responseData()) + length(response.name()) + length(response.id());
            }
        } else {
            chars += length(message.getText());
            if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    chars += length(call.name()) + length(call.arguments()) + length(call.id());
                }
            }
        }
        return MESSAGE_OVERHEAD_TOKENS + (int) Math.ceil(chars / charsPerToken);
    }

    /** Rough token cost of the tool schemas, which every request carries in full. */
    static int estimateToolTokens(List<ToolCallback> callbacks) {
        return toolTokens(callbacks, DEFAULT_CHARS_PER_TOKEN);
    }

    private static int toolTokens(List<ToolCallback> callbacks, double charsPerToken) {
        int chars = 0;
        for (ToolCallback callback : callbacks) {
            var definition = callback.getToolDefinition();
            chars += length(definition.name()) + length(definition.description())
                    + length(definition.inputSchema());
        }
        return callbacks.size() * MESSAGE_OVERHEAD_TOKENS + (int) Math.ceil(chars / charsPerToken);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
