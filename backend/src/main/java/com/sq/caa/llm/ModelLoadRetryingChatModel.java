package com.sq.caa.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * A {@link ChatModel} that retries one call after asking LM Studio to load the model, when the
 * failure is LM Studio's "not loaded" signature. Wraps the concrete model in
 * {@link OpenAiLlmClientFactory}, so every caller (probes, ReAct loop, rule judge) gets the
 * behavior; other errors pass through untouched.
 */
public class ModelLoadRetryingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final LmStudioModelLoader loader;

    public ModelLoadRetryingChatModel(ChatModel delegate, LmStudioModelLoader loader) {
        this.delegate = delegate;
        this.loader = loader;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return loader.callWithLoadRetry(() -> delegate.call(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return loader.streamWithLoadRetry(() -> delegate.stream(prompt));
    }

    @Override
    public ChatOptions getOptions() {
        // ChatModel.getOptions() is a default method returning null unless overridden;
        // RiskAgentLoop.modelId() reads it to record which model served a run.
        return delegate.getOptions();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
