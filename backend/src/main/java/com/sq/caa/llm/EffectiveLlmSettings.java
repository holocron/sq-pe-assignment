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
 * @param apiKey         the credential sent to the endpoint; never leaves the server
 * @param source         {@code "database"} when an admin row drives this, {@code "environment"}
 *                       when the boot configuration does
 * @param updatedAt      when the database row was saved; null for the environment source
 * @param updatedBy      who saved the database row; null for the environment source
 */
public record EffectiveLlmSettings(String baseUrl, String chatModel, String embedModel,
        int embedDimension, String apiKey, String source, Instant updatedAt, String updatedBy) {

    public static final String SOURCE_DATABASE = "database";
    public static final String SOURCE_ENVIRONMENT = "environment";

    /**
     * Whether a real credential is configured. The environment placeholder {@code "none"} (the
     * documented boot default in {@code application.yml}) counts as <em>not set</em>: it exists
     * only because some servers insist an Authorization header be present.
     */
    public boolean apiKeySet() {
        return apiKey != null && !apiKey.isBlank() && !"none".equalsIgnoreCase(apiKey.strip());
    }
}
