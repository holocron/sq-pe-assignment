package com.sq.caa.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A {@link ChatModel} that answers from a fixed script instead of from a language model.
 *
 * <p>The coverage gate is a property of the loop, not of the model, so it has to be testable without
 * one. This fake lets a test say "on turn three the model tries to finish while two rules are still
 * open" and then assert what the loop did about it. Every prompt the loop built is recorded, so the
 * test can also check that the reprompt actually named the missing rules.
 */
final class ScriptedChatModel implements ChatModel {

    private final List<AssistantMessage> script;
    private final List<Prompt> prompts = new ArrayList<>();
    private final AtomicInteger turn = new AtomicInteger();

    ScriptedChatModel(List<AssistantMessage> script) {
        this.script = List.copyOf(script);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        int index = turn.getAndIncrement();
        AssistantMessage message = index < script.size()
                ? script.get(index)
                : new AssistantMessage("I have nothing further to add.");
        return new ChatResponse(List.of(new Generation(message)));
    }

    /** How many turns the loop actually consumed. */
    int turns() {
        return turn.get();
    }

    /** Every prompt the loop sent, in order. */
    List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    /** The text of every user message the loop ever sent, including the reprompts. */
    List<String> userMessages() {
        List<String> texts = new ArrayList<>();
        for (Prompt prompt : prompts) {
            for (Message message : prompt.getInstructions()) {
                if (message.getMessageType() == MessageType.USER) {
                    texts.add(message.getText());
                }
            }
        }
        return texts;
    }

    // ------------------------------------------------------------------
    // Script building
    // ------------------------------------------------------------------

    /** A turn where the model just talks - which, for this loop, means "I am finished". */
    static AssistantMessage says(String text) {
        return new AssistantMessage(text);
    }

    /** A turn where the model calls one tool. */
    static AssistantMessage calls(String tool, String argumentsJson) {
        return callsAll(new AssistantMessage.ToolCall("call-" + tool + "-" + COUNTER.incrementAndGet(),
                "function", tool, argumentsJson));
    }

    /** A turn where the model calls several tools at once. */
    static AssistantMessage callsAll(AssistantMessage.ToolCall... calls) {
        return AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();
}
