package com.sq.caa.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * The tooling {@code ChatModel} bean: the sibling of {@link DelegatingChatModel} that forwards
 * every call to the model {@link MutableLlmSettingsService} has built for the
 * {@code toolModel} of the configuration in effect <em>at call time</em>.
 *
 * <p>The rule subagents' ReAct mini-loops inject this shell (by qualifier) instead of the primary
 * chat model, so a deployment can run tool-driving on a smaller, faster model while the
 * orchestrator's closing summary, the rule judge and the Enhance wand stay on the reasoning model.
 * The tooling model shares the chat endpoint and the chat credential; only the model id differs.
 */
public class DelegatingToolingChatModel implements ChatModel {

    private final MutableLlmSettingsService settingsService;

    public DelegatingToolingChatModel(MutableLlmSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return settingsService.toolingChatModel().call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return settingsService.toolingChatModel().stream(prompt);
    }

    @Override
    public ChatOptions getOptions() {
        // Same contract as DelegatingChatModel: RiskAgentLoop.toolingModelId() reads it to record
        // which tooling model a run used, so it must delegate.
        return settingsService.toolingChatModel().getOptions();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return settingsService.toolingChatModel().getDefaultOptions();
    }
}
