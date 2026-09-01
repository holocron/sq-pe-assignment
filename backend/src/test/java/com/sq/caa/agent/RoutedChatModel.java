package com.sq.caa.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A thread-safe {@link ChatModel} scripted by prompt content: each rule subagent's conversation is
 * recognised by the rule name in its task message, the orchestrator's closing conversation by the
 * {@value #SUMMARY_MARKER} of its verdict table, and each route gets its own queue of scripted turns.
 *
 * <p>The single-conversation {@link ScriptedChatModel} cannot drive the orchestrator architecture:
 * subagents run concurrently on their own threads, so "turn three" is meaningless across them while
 * "the conversation about THIS rule" is exact. A retry of a failed subagent re-sends the same task
 * message, so one route's queue simply continues into the retry - script both attempts in order.
 *
 * <p>Every prompt is recorded, per-route call counts are kept, and the peak number of in-flight
 * model calls is measured so a test can assert the parallelism bound was actually respected.
 */
final class RoutedChatModel implements ChatModel {

    /** Marks the closing conversation: the summary task fences its verdict table under this label. */
    static final String SUMMARY_MARKER = "verdict_table";

    private final Map<String, Queue<ScriptedChatModel.Turn>> routes = new LinkedHashMap<>();
    private final Queue<ScriptedChatModel.Turn> fallback = new ConcurrentLinkedQueue<>();
    private final Map<String, AtomicInteger> callsPerRoute = new LinkedHashMap<>();
    private final List<Prompt> prompts = new ArrayList<>();
    private final Object lock = new Object();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    /** Scripts the conversation whose prompts contain {@code marker} - e.g. a rule's name. */
    RoutedChatModel route(String marker, List<ScriptedChatModel.Turn> turns) {
        synchronized (lock) {
            routes.put(marker, new ConcurrentLinkedQueue<>(turns));
            callsPerRoute.put(marker, new AtomicInteger());
        }
        return this;
    }

    /** Scripts the orchestrator's closing summary conversation. */
    RoutedChatModel summary(List<ScriptedChatModel.Turn> turns) {
        return route(SUMMARY_MARKER, turns);
    }

    /** Turns served when no route matches; beyond them the model just talks. */
    RoutedChatModel otherwise(List<ScriptedChatModel.Turn> turns) {
        fallback.addAll(turns);
        return this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            String text = promptText(prompt);
            synchronized (lock) {
                prompts.add(prompt);
            }
            // The closing conversation's verdict table names every rule, so the summary route is
            // checked first - its marker appears in no subagent prompt.
            Queue<ScriptedChatModel.Turn> summaryRoute = routes.get(SUMMARY_MARKER);
            if (summaryRoute != null && text.contains(SUMMARY_MARKER)) {
                callsPerRoute.get(SUMMARY_MARKER).incrementAndGet();
                ScriptedChatModel.Turn turn = summaryRoute.poll();
                if (turn != null) {
                    return turn.respond();
                }
            } else {
                for (Map.Entry<String, Queue<ScriptedChatModel.Turn>> route : routes.entrySet()) {
                    if (!SUMMARY_MARKER.equals(route.getKey()) && text.contains(route.getKey())) {
                        callsPerRoute.get(route.getKey()).incrementAndGet();
                        ScriptedChatModel.Turn turn = route.getValue().poll();
                        if (turn != null) {
                            return turn.respond();
                        }
                        break;
                    }
                }
            }
            ScriptedChatModel.Turn turn = fallback.poll();
            return turn == null
                    ? new ChatResponse(List.of(new Generation(
                            new AssistantMessage("I have nothing further to add."))))
                    : turn.respond();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** How often the conversation matching {@code marker} has called the model. */
    int calls(String marker) {
        AtomicInteger count = callsPerRoute.get(marker);
        return count == null ? 0 : count.get();
    }

    /** The peak number of model calls in flight at once; the parallelism bound must never be exceeded. */
    int peakConcurrency() {
        return maxInFlight.get();
    }

    /** Every prompt sent, in arrival order (arrival order is racy under parallelism). */
    List<Prompt> prompts() {
        synchronized (lock) {
            return List.copyOf(prompts);
        }
    }

    private static String promptText(Prompt prompt) {
        StringBuilder text = new StringBuilder();
        prompt.getInstructions().forEach(message -> text.append('\n').append(message.getText()));
        return text.toString();
    }
}
