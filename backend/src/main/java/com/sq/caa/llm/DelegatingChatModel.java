package com.sq.caa.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * The application's {@code ChatModel} bean: a stable shell that forwards every call to the model
 * {@link MutableLlmSettingsService} has built for the configuration in effect <em>at call time</em>.
 *
 * <p>The delegate instance is captured per call, so a settings change mid-analysis never swaps the
 * client underneath an in-flight conversation - that run finishes on the configuration it started
 * with, and the next call picks up the new one.
 */
public class DelegatingChatModel implements ChatModel {

    private final MutableLlmSettingsService settingsService;

    public DelegatingChatModel(MutableLlmSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return settingsService.chatModel().call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return settingsService.chatModel().stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return settingsService.chatModel().getDefaultOptions();
    }
}
