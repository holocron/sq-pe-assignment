package com.sq.caa.web.dto;

import com.sq.caa.llm.EffectiveLlmSettings;
import com.sq.caa.llm.MutableLlmSettingsService;
import com.sq.caa.llm.ReembedStatus;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

/**
 * The request and response shapes of {@code /api/admin/llm-settings}.
 *
 * <p>The API keys are write-only: they appear in request bodies, never in a response.
 */
public final class LlmSettingsDtos {

    private LlmSettingsDtos() {
    }

    /** The effective settings. {@code updatedAt}/{@code updatedBy} are null for source=environment. */
    public record LlmSettingsDto(String baseUrl, String chatModel, String embedModel,
            int embedDimension, boolean chatApiKeySet, boolean embedApiKeySet, String source,
            Instant updatedAt, String updatedBy) {

        public static LlmSettingsDto from(EffectiveLlmSettings settings) {
            return new LlmSettingsDto(settings.baseUrl(), settings.chatModel(), settings.embedModel(),
                    settings.embedDimension(), settings.chatApiKeySet(), settings.embedApiKeySet(),
                    settings.source(), settings.updatedAt(), settings.updatedBy());
        }
    }

    /**
     * A PUT body. Per key ({@code chatApiKey} / {@code embedApiKey}): omitted or null keeps the
     * current key; an empty string is an explicit "no key" (local model servers); any other value
     * sets the key. {@code confirmReembed} must be true to save an embedding-model change.
     */
    public record LlmSettingsUpdateRequest(
            @NotBlank(message = "must not be blank") String baseUrl,
            @NotBlank(message = "must not be blank") String chatModel,
            @NotBlank(message = "must not be blank") String embedModel,
            String chatApiKey,
            String embedApiKey,
            Boolean confirmReembed) {

        public MutableLlmSettingsService.UpdateCommand toCommand() {
            return new MutableLlmSettingsService.UpdateCommand(baseUrl, chatModel, embedModel,
                    chatApiKey, embedApiKey, Boolean.TRUE.equals(confirmReembed));
        }
    }

    /** The PUT answer: the saved settings plus whether the re-embed job was started. */
    public record LlmSettingsSavedDto(String baseUrl, String chatModel, String embedModel,
            int embedDimension, boolean chatApiKeySet, boolean embedApiKeySet, String source,
            Instant updatedAt, String updatedBy, boolean reembedStarted) {

        public static LlmSettingsSavedDto from(EffectiveLlmSettings settings, boolean reembedStarted) {
            return new LlmSettingsSavedDto(settings.baseUrl(), settings.chatModel(),
                    settings.embedModel(), settings.embedDimension(), settings.chatApiKeySet(),
                    settings.embedApiKeySet(), settings.source(), settings.updatedAt(),
                    settings.updatedBy(), reembedStarted);
        }
    }

    /** {@code GET .../models}: the model ids the candidate endpoint advertises. */
    public record ModelsResponse(List<String> models) {
    }

    /**
     * A {@code POST .../test} body. Per key, an omitted field means "the currently stored key for
     * that model"; the chat probe uses {@code chatApiKey}, the embed probe {@code embedApiKey}.
     */
    public record LlmTestRequest(
            @NotBlank(message = "must not be blank") String baseUrl,
            @NotBlank(message = "must not be blank") String chatModel,
            @NotBlank(message = "must not be blank") String embedModel,
            String chatApiKey,
            String embedApiKey) {
    }

    public record ChatProbeDto(boolean ok, String detail) {
    }

    public record EmbedProbeDto(boolean ok, String detail, Integer dimension) {
    }

    public record LlmTestResponse(ChatProbeDto chat, EmbedProbeDto embed) {

        public static LlmTestResponse from(MutableLlmSettingsService.ConnectionTestResult result) {
            return new LlmTestResponse(
                    new ChatProbeDto(result.chat().ok(), result.chat().detail()),
                    new EmbedProbeDto(result.embed().ok(), result.embed().detail(),
                            result.embed().dimension()));
        }
    }

    /** {@code GET .../reembed-status}. */
    public record ReembedStatusDto(boolean running, int totalDocuments, int completedDocuments,
            int failedDocuments, String lastError) {

        public static ReembedStatusDto from(ReembedStatus status) {
            return new ReembedStatusDto(status.running(), status.totalDocuments(),
                    status.completedDocuments(), status.failedDocuments(), status.lastError());
        }
    }
}
