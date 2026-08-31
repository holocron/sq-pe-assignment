package com.sq.caa.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.stereotype.Component;

/**
 * Builds Spring AI 2.x OpenAI clients from an {@link EffectiveLlmSettings}.
 *
 * <p>This mirrors what the OpenAI auto-configuration does once at startup - the official OpenAI
 * SDK client over Spring AI's OkHttp transport - except it does it on demand, so a settings change
 * produces a new client without a restart. The timeouts and retry count come from the same
 * {@code spring.ai.openai.*} properties the auto-configuration reads (carried in
 * {@link LlmDefaults}), because the values tuned for the local reasoning model (see
 * {@code application.yml}) apply however the endpoint was chosen.
 */
@Component
public class OpenAiLlmClientFactory implements LlmClientFactory {

    /** The credential placeholder sent when no key is configured; lemonade accepts anything. */
    private static final String NO_KEY = "none";

    private final LlmDefaults defaults;

    public OpenAiLlmClientFactory(LlmDefaults defaults) {
        this.defaults = defaults;
    }

    @Override
    public ChatModel chatModel(EffectiveLlmSettings settings) {
        return OpenAiChatModel.builder()
                .openAiClient(openAiClient(settings))
                .options(OpenAiChatOptions.builder()
                        .model(settings.chatModel())
                        .temperature(defaults.temperature())
                        .maxTokens(defaults.maxTokens())
                        // Copied into the SDK's per-request options; without these the builder's
                        // 60s/3-retries defaults apply (see application.yml for why that kills a
                        // long reasoning turn).
                        .timeout(defaults.timeout())
                        .maxRetries(defaults.maxRetries())
                        .build())
                .build();
    }

    @Override
    public EmbeddingModel embeddingModel(EffectiveLlmSettings settings) {
        return OpenAiEmbeddingModel.builder()
                .openAiClient(openAiClient(settings))
                .metadataMode(MetadataMode.EMBED)
                .options(OpenAiEmbeddingOptions.builder()
                        .model(settings.embedModel())
                        .build())
                .build();
    }

    private OpenAIClient openAiClient(EffectiveLlmSettings settings) {
        String apiKey = settings.apiKey() == null || settings.apiKey().isBlank()
                ? NO_KEY : settings.apiKey();
        return new OpenAIClientImpl(ClientOptions.builder()
                .httpClient(SpringAiOpenAiHttpClient.builder()
                        .timeout(defaults.timeout())
                        .build())
                .baseUrl(settings.baseUrl())
                .apiKey(apiKey)
                .timeout(defaults.timeout())
                .maxRetries(defaults.maxRetries())
                .build());
    }
}
