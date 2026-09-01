package com.sq.caa.llm;

import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the runtime-configurable LLM layer.
 *
 * <p>The {@code chatModel} and {@code embeddingModel} beans are the delegating shells - marked
 * {@code @Primary} so the Spring AI auto-configurations (which may also build an OpenAI model from
 * the boot properties) never win an injection point. Every consumer - the orchestrator's closing
 * summary, the rule judge, the Enhance wand, the pgvector store - therefore transparently follows
 * the admin-editable settings. The {@code toolingChatModel} bean is the second chat shell, NOT
 * primary: only the rule subagents' ReAct mini-loops inject it (by qualifier), and it follows the
 * settings' {@code toolModel}.
 */
@Configuration
public class LlmConfiguration {

    /** The environment configuration, i.e. the fallback when no {@code llm_settings} row exists. */
    @Bean
    public LlmDefaults llmDefaults(
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model}") String chatModel,
            @Value("${caa.llm.tool-model:}") String toolModel,
            @Value("${spring.ai.openai.embedding.options.model}") String embedModel,
            @Value("${spring.ai.vectorstore.pgvector.dimensions:1536}") int embedDimension,
            @Value("${spring.ai.openai.timeout:10m}") Duration timeout,
            @Value("${spring.ai.openai.max-retries:1}") int maxRetries,
            @Value("${spring.ai.openai.chat.options.temperature:0.1}") double temperature,
            @Value("${spring.ai.openai.chat.options.max-tokens:4096}") int maxTokens) {
        return new LlmDefaults(baseUrl, apiKey, chatModel, toolModel, embedModel, embedDimension,
                timeout, maxRetries, temperature, maxTokens);
    }

    @Bean
    @Primary
    public ChatModel chatModel(MutableLlmSettingsService settingsService) {
        return new DelegatingChatModel(settingsService);
    }

    /**
     * The tooling chat shell - the model the rule subagents' tool-driving mini-loops run on. Not
     * {@code @Primary}: nothing may pick it up by accident; consumers qualify for it explicitly.
     */
    @Bean
    public ChatModel toolingChatModel(MutableLlmSettingsService settingsService) {
        return new DelegatingToolingChatModel(settingsService);
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(MutableLlmSettingsService settingsService) {
        return new DelegatingEmbeddingModel(settingsService);
    }
}
