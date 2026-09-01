package com.sq.caa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Client construction in {@link OpenAiLlmClientFactory}: every key shape must produce a built
 * chat and embedding model without touching the network. Regression for the 2026-08-31 incident
 * where a blank key killed {@code OpenAiChatModel.Builder.build()} with "At least one credential
 * source must be specified" - the builder also materializes an <em>async</em> client, which fell
 * back to {@code OpenAiSetup.setupAsyncClient} and read a key that was never in the options.
 */
class OpenAiLlmClientFactoryTest {

    private static final LlmDefaults DEFAULTS = new LlmDefaults(
            "http://localhost:13305/api/v1", "", "chat-model", "tool-model", "embed-model", 2560,
            Duration.ofMinutes(10), 1, 0.1, 4096);

    private final OpenAiLlmClientFactory factory = new OpenAiLlmClientFactory(DEFAULTS);

    private static EffectiveLlmSettings settings(String chatApiKey, String embedApiKey) {
        return new EffectiveLlmSettings("http://localhost:13305/api/v1", "chat-model",
                "tool-model", "embed-model", 2560, chatApiKey, embedApiKey, "test", null, null);
    }

    @ParameterizedTest(name = "blank key [{0}] builds chat and embedding models keyless")
    @NullSource
    @ValueSource(strings = {"", "   ", "none", "NONE"})
    void blankOrPlaceholderKeyBuildsKeylessClients(String key) {
        EffectiveLlmSettings settings = settings(key, key);
        assertThat(factory.chatModel(settings)).isNotNull();
        assertThat(factory.embeddingModel(settings)).isNotNull();
    }

    @Test
    @DisplayName("a real key builds both models")
    void realKeyBuildsClients() {
        EffectiveLlmSettings settings = settings("sk-chat", "sk-embed");
        assertThat(factory.chatModel(settings)).isNotNull();
        assertThat(factory.embeddingModel(settings)).isNotNull();
    }

    @Test
    @DisplayName("keys are per model: a blank embed key beside a real chat key still builds")
    void mixedKeysBuildClients() {
        EffectiveLlmSettings settings = settings("sk-chat", "");
        assertThat(factory.chatModel(settings)).isNotNull();
        assertThat(factory.embeddingModel(settings)).isNotNull();
    }

    @Test
    @DisplayName("both models are wrapped in the LM Studio load-and-retry decorator")
    void modelsAreLoadRetrying() {
        EffectiveLlmSettings settings = settings("", "");
        assertThat(factory.chatModel(settings)).isInstanceOf(ModelLoadRetryingChatModel.class);
        assertThat(factory.embeddingModel(settings)).isInstanceOf(ModelLoadRetryingEmbeddingModel.class);
    }

    /**
     * Call-time regression: a chat call that supplies its own {@link OpenAiChatOptions} (as
     * {@code probeChat}, the ReAct loop and the rule judge all do) must still work with no key -
     * the credential lives on the client, and Spring AI 2.0.1 does not rebuild the client from
     * the per-request options. Needs the live router; run with {@code -Dtest.excludedGroups=}.
     */
    @Test
    @Tag("live")
    @DisplayName("keyless chat call with per-request options")
    void keylessChatCallWithPerRequestOptions() {
        ChatModel model = factory.chatModel(settings("", ""));
        ChatResponse response = model.call(new Prompt("Reply with the single word: ok",
                OpenAiChatOptions.builder()
                        .model("gpt-oss-120b-GGUF")
                        .maxTokens(8)
                        .maxRetries(0)
                        .build()));
        // A reasoning model can spend the tiny token budget on thought and return empty content;
        // the assertion that matters is that the keyless call was answered at all.
        assertThat(response.getResult()).isNotNull();
    }
}
