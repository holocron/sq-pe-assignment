package com.sq.caa.llm;

import java.time.Instant;

/**
 * The configuration the LLM clients run on right now: the admin's {@code llm_settings} row when
 * one exists, otherwise the environment ({@code LLM_BASE_URL} / {@code LLM_CHAT_MODEL} /
 * {@code LLM_EMBED_MODEL} / {@code OPENAI_API_KEY}) the application booted with.
 *
 * @param baseUrl        OpenAI-compatible endpoint, including {@code /v1}
 * @param chatModel      model id used for chat completions
 * @param embedModel     model id used for embeddings
 * @param embedDimension the vector length {@code document_chunks.embedding} currently holds
 * @param chatApiKey     the credential sent for chat calls; never leaves the server
 * @param embedApiKey    the credential sent for embedding calls; never leaves the server
 * @param source         {@code "database"} when an admin row drives this, {@code "environment"}
 *                       when the boot configuration does
 * @param updatedAt      when the database row was saved; null for the environment source
 * @param updatedBy      who saved the database row; null for the environment source
 */
public record EffectiveLlmSettings(String baseUrl, String chatModel, String embedModel,
        int embedDimension, String chatApiKey, String embedApiKey, String source,
        Instant updatedAt, String updatedBy) {

    public static final String SOURCE_DATABASE = "database";
    public static final String SOURCE_ENVIRONMENT = "environment";

    /**
     * Legacy placeholder for "no credential" ({@code OPENAI_API_KEY=none} was the documented boot
     * default when the SDK could not be built keyless). It still counts as <em>not set</em>; the
     * clients now send no Authorization header at all for it (see
     * {@link OpenAiLlmClientFactory}).
     */
    public static final String NO_KEY_PLACEHOLDER = "none";

    /** Whether a real chat credential is configured. */
    public boolean chatApiKeySet() {
        return isSet(chatApiKey);
    }

    /** Whether a real embedding credential is configured. */
    public boolean embedApiKeySet() {
        return isSet(embedApiKey);
    }

    /** A key is set when it is non-blank and not the boot {@value #NO_KEY_PLACEHOLDER}. */
    private static boolean isSet(String apiKey) {
        return apiKey != null && !apiKey.isBlank()
                && !NO_KEY_PLACEHOLDER.equalsIgnoreCase(apiKey.strip());
    }
}
