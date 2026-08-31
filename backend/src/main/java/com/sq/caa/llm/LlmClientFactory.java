package com.sq.caa.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Builds the real Spring AI model clients for one configuration.
 *
 * <p>An interface so the delegating models and the settings service can be unit-tested without a
 * live endpoint; the production implementation is {@link OpenAiLlmClientFactory}.
 */
public interface LlmClientFactory {

    /** A chat model bound to {@code settings.baseUrl}/{@code settings.chatModel}. */
    ChatModel chatModel(EffectiveLlmSettings settings);

    /** An embedding model bound to {@code settings.baseUrl}/{@code settings.embedModel}. */
    EmbeddingModel embeddingModel(EffectiveLlmSettings settings);
}
