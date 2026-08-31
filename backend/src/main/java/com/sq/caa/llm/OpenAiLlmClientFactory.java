package com.sq.caa.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.http.HttpRequestAuthenticator;
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
 *
 * <p><b>Blank key = no credential.</b> A blank key means "no credential" (local model servers),
 * so the client is then built keyless - requests carry no {@code Authorization} header at all
 * ({@link #makeKeyless}; the SDK allows it via a no-op {@code httpRequestAuthenticator}, a
 * Kotlin-only setter reached reflectively). Local servers such as lemonade or LM Studio ignore
 * authentication anyway; a dummy header value would only be needed for a server that rejects
 * requests without one, in which case a real key belongs in the settings. The legacy
 * {@code "none"} placeholder ({@link EffectiveLlmSettings#NO_KEY_PLACEHOLDER}) is treated as
 * blank for the same reason.
 *
 * <p><b>LM Studio model loading.</b> Both models are wrapped in a load-and-retry decorator
 * ({@link LmStudioModelLoader}): LM Studio has no JIT loading by default and answers a call for
 * an unloaded model with {@code 400 "Failed to load model"}, so on that exact signature the
 * wrapper asks the endpoint to load the model and retries once. Other errors pass through.
 */
@Component
public class OpenAiLlmClientFactory implements LlmClientFactory {

    private final LlmDefaults defaults;

    public OpenAiLlmClientFactory(LlmDefaults defaults) {
        this.defaults = defaults;
    }

    @Override
    public ChatModel chatModel(EffectiveLlmSettings settings) {
        ChatModel model = OpenAiChatModel.builder()
                .openAiClient(openAiClient(settings, settings.chatApiKey()))
                // The builder ALSO materializes an async client (for streaming); left unset it
                // falls back to OpenAiSetup.setupAsyncClient, which reads the key from the
                // options - never populated here - and throws the SDK's "At least one credential
                // source must be specified" even though the sync client carries one.
                .openAiClientAsync(openAiClientAsync(settings, settings.chatApiKey()))
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
        return new ModelLoadRetryingChatModel(model,
                new LmStudioModelLoader(settings.baseUrl(), settings.chatModel(), defaults.timeout()));
    }

    @Override
    public EmbeddingModel embeddingModel(EffectiveLlmSettings settings) {
        EmbeddingModel model = OpenAiEmbeddingModel.builder()
                .openAiClient(openAiClient(settings, settings.embedApiKey()))
                .metadataMode(MetadataMode.EMBED)
                .options(OpenAiEmbeddingOptions.builder()
                        .model(settings.embedModel())
                        .build())
                .build();
        return new ModelLoadRetryingEmbeddingModel(model,
                new LmStudioModelLoader(settings.baseUrl(), settings.embedModel(), defaults.timeout()));
    }

    private OpenAIClient openAiClient(EffectiveLlmSettings settings, String apiKey) {
        return new OpenAIClientImpl(clientOptions(settings, apiKey));
    }

    private OpenAIClientAsync openAiClientAsync(EffectiveLlmSettings settings, String apiKey) {
        return new OpenAIClientAsyncImpl(clientOptions(settings, apiKey));
    }

    private ClientOptions clientOptions(EffectiveLlmSettings settings, String apiKey) {
        ClientOptions.Builder builder = ClientOptions.builder()
                .httpClient(SpringAiOpenAiHttpClient.builder()
                        .timeout(defaults.timeout())
                        .build())
                .baseUrl(settings.baseUrl())
                .timeout(defaults.timeout())
                .maxRetries(defaults.maxRetries());
        boolean noKey = apiKey == null || apiKey.isBlank()
                || EffectiveLlmSettings.NO_KEY_PLACEHOLDER.equalsIgnoreCase(apiKey.strip());
        if (noKey) {
            makeKeyless(builder);
        } else {
            builder.apiKey(apiKey);
        }
        return builder.build();
    }

    /**
     * Builds the SDK client with NO credential source, so requests carry no
     * {@code Authorization} header at all. The SDK supports this through
     * {@code ClientOptions.Builder.httpRequestAuthenticator} with a no-op authenticator, but
     * that setter is Kotlin-only ({@code ACC_SYNTHETIC} - javac cannot see it), so it is invoked
     * reflectively. Verified against openai-java 4.49.0: a keyless client embeds successfully
     * against the local router with no header sent.
     */
    private static void makeKeyless(ClientOptions.Builder builder) {
        try {
            ClientOptions.Builder.class
                    .getMethod("httpRequestAuthenticator", HttpRequestAuthenticator.class)
                    .invoke(builder, (HttpRequestAuthenticator) request -> request);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "The OpenAI SDK no longer exposes httpRequestAuthenticator; a keyless client"
                            + " cannot be built. Pin the key or revisit this path.", e);
        }
    }
}
