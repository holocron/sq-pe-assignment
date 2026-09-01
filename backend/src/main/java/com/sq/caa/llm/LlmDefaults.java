package com.sq.caa.llm;

import java.time.Duration;

/**
 * The boot-time defaults from the environment, i.e. what {@code llm_settings} overrides when an
 * admin row exists.
 *
 * @param baseUrl        {@code spring.ai.openai.base-url} ({@code LLM_BASE_URL})
 * @param apiKey         {@code spring.ai.openai.api-key} ({@code OPENAI_API_KEY})
 * @param chatModel      {@code spring.ai.openai.chat.options.model} ({@code LLM_CHAT_MODEL})
 * @param toolModel      {@code caa.llm.tool-model} ({@code LLM_TOOL_MODEL}); blank means "the chat
 *                       model" - the subagents then run on the reasoning model, as before V9
 * @param embedModel     {@code spring.ai.openai.embedding.options.model} ({@code LLM_EMBED_MODEL})
 * @param embedDimension {@code spring.ai.vectorstore.pgvector.dimensions}
 * @param timeout        {@code spring.ai.openai.timeout} - the shared per-request bound
 * @param maxRetries     {@code spring.ai.openai.max-retries}
 * @param temperature    {@code spring.ai.openai.chat.options.temperature}
 * @param maxTokens      {@code spring.ai.openai.chat.options.max-tokens}
 */
public record LlmDefaults(String baseUrl, String apiKey, String chatModel, String toolModel,
        String embedModel, int embedDimension, Duration timeout, int maxRetries, double temperature,
        int maxTokens) {
}
