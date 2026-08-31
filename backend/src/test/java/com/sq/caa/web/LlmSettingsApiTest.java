package com.sq.caa.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sq.caa.config.GlobalExceptionHandler;
import com.sq.caa.llm.EffectiveLlmSettings;
import com.sq.caa.llm.LlmEndpointException;
import com.sq.caa.llm.MutableLlmSettingsService;
import com.sq.caa.llm.ReembedConfirmationRequiredException;
import com.sq.caa.llm.ReembedService;
import com.sq.caa.llm.ReembedStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The web contract of {@code /api/admin/llm-settings}: the response shapes the frontend codes
 * against, the 409 confirmation flow for embedding-model changes, and 502 when a candidate
 * endpoint cannot be reached.
 *
 * <p>The service and the re-embed job are mocked - what is under test is the API, not the model.
 */
class LlmSettingsApiTest {

    private MutableLlmSettingsService settingsService;
    private ReembedService reembedService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        settingsService = mock(MutableLlmSettingsService.class);
        reembedService = mock(ReembedService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new LlmSettingsController(settingsService, reembedService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static EffectiveLlmSettings settings(String source) {
        boolean database = "database".equals(source);
        return new EffectiveLlmSettings("http://llm:8000/v1", "chat-x", "embed-y", 1024,
                database ? "sk-chat" : "none", database ? "sk-embed" : "", source,
                database ? Instant.parse("2026-08-31T10:00:00Z") : null,
                database ? "admin" : null);
    }

    @Test
    @DisplayName("GET returns the effective settings and never the API keys")
    void getReturnsTheShape() throws Exception {
        when(settingsService.effective()).thenReturn(settings("database"));

        mockMvc.perform(get("/api/admin/llm-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("http://llm:8000/v1"))
                .andExpect(jsonPath("$.chatModel").value("chat-x"))
                .andExpect(jsonPath("$.embedModel").value("embed-y"))
                .andExpect(jsonPath("$.embedDimension").value(1024))
                .andExpect(jsonPath("$.chatApiKeySet").value(true))
                .andExpect(jsonPath("$.embedApiKeySet").value(true))
                .andExpect(jsonPath("$.source").value("database"))
                .andExpect(jsonPath("$.updatedAt").value("2026-08-31T10:00:00Z"))
                .andExpect(jsonPath("$.updatedBy").value("admin"))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.apiKeySet").doesNotExist())
                .andExpect(jsonPath("$.chatApiKey").doesNotExist())
                .andExpect(jsonPath("$.embedApiKey").doesNotExist());
    }

    @Test
    @DisplayName("PUT with a blank model is a 400 validation problem")
    void putValidatesTheBody() throws Exception {
        mockMvc.perform(put("/api/admin/llm-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"http://x/v1\",\"chatModel\":\" \",\"embedModel\":\"e\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.chatModel").exists());
    }

    @Test
    @DisplayName("PUT an unconfirmed embedding-model change answers 409 problem+json")
    void putUnconfirmedEmbedChangeIs409() throws Exception {
        when(settingsService.update(any(), anyString()))
                .thenThrow(new ReembedConfirmationRequiredException("embed-y", "embed-z"));

        mockMvc.perform(put("/api/admin/llm-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"http://x/v1\",\"chatModel\":\"c\","
                                + "\"embedModel\":\"embed-z\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value("Embedding model change requires re-embedding"))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("PUT a confirmed change saves and reports whether the re-embed job started")
    void putConfirmedChangeStartsReembed() throws Exception {
        when(settingsService.update(any(), anyString()))
                .thenReturn(new MutableLlmSettingsService.UpdateOutcome(settings("database"), true));
        when(reembedService.start()).thenReturn(true);

        mockMvc.perform(put("/api/admin/llm-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"http://x/v1\",\"chatModel\":\"c\","
                                + "\"embedModel\":\"embed-z\",\"chatApiKey\":\"sk-c\","
                                + "\"embedApiKey\":\"\",\"confirmReembed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedModel").value("embed-y"))
                .andExpect(jsonPath("$.source").value("database"))
                .andExpect(jsonPath("$.reembedStarted").value(true))
                .andExpect(jsonPath("$.chatApiKeySet").value(true))
                .andExpect(jsonPath("$.chatApiKey").doesNotExist())
                .andExpect(jsonPath("$.embedApiKey").doesNotExist());
    }

    @Test
    @DisplayName("GET models proxies the endpoint's model list")
    void modelsReturnsTheList() throws Exception {
        when(settingsService.listModels(anyString(), isNull()))
                .thenReturn(List.of("gpt-oss-120b-GGUF", "Qwen3-Embedding-4B-GGUF"));

        mockMvc.perform(get("/api/admin/llm-settings/models").param("baseUrl", "http://x/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0]").value("gpt-oss-120b-GGUF"))
                .andExpect(jsonPath("$.models[1]").value("Qwen3-Embedding-4B-GGUF"));
    }

    @Test
    @DisplayName("GET models against an unreachable endpoint is a 502 problem+json")
    void modelsUnreachableIs502() throws Exception {
        when(settingsService.listModels(anyString(), isNull()))
                .thenThrow(new LlmEndpointException(
                        "The endpoint http://x/v1/models could not be reached: Connection refused"));

        mockMvc.perform(get("/api/admin/llm-settings/models").param("baseUrl", "http://x/v1"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("LLM endpoint unavailable"))
                .andExpect(jsonPath("$.detail").value(
                        "The endpoint http://x/v1/models could not be reached: Connection refused"));
    }

    @Test
    @DisplayName("POST test probes chat and embedding and reports per-side results")
    void testReturnsBothProbes() throws Exception {
        when(settingsService.testConnection(anyString(), anyString(), anyString(), isNull(), isNull()))
                .thenReturn(new MutableLlmSettingsService.ConnectionTestResult(
                        new MutableLlmSettingsService.ChatProbe(true, "Chat model 'c' answered."),
                        new MutableLlmSettingsService.EmbedProbe(false, "connection refused", null)));

        mockMvc.perform(post("/api/admin/llm-settings/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"http://x/v1\",\"chatModel\":\"c\",\"embedModel\":\"e\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chat.ok").value(true))
                .andExpect(jsonPath("$.chat.detail").exists())
                .andExpect(jsonPath("$.embed.ok").value(false))
                .andExpect(jsonPath("$.embed.detail").value("connection refused"))
                .andExpect(jsonPath("$.embed.dimension").doesNotExist());
    }

    @Test
    @DisplayName("GET reembed-status returns the job snapshot")
    void reembedStatusReturnsTheSnapshot() throws Exception {
        when(reembedService.status())
                .thenReturn(new ReembedStatus(true, 5, 2, 1, "old.pdf: no stored source bytes"));

        mockMvc.perform(get("/api/admin/llm-settings/reembed-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.totalDocuments").value(5))
                .andExpect(jsonPath("$.completedDocuments").value(2))
                .andExpect(jsonPath("$.failedDocuments").value(1))
                .andExpect(jsonPath("$.lastError").value("old.pdf: no stored source bytes"));
    }
}
