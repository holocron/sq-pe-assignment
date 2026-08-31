package com.sq.caa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sq.caa.domain.LlmSettings;
import com.sq.caa.repository.LlmSettingsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The resolution and update rules of {@link MutableLlmSettingsService}: the environment is the
 * fallback, a database row overrides it, the clients are rebuilt when the configuration changes,
 * and an embedding-model change is guarded by the re-embed confirmation and the dimension probe.
 *
 * <p>The repository, the client factory and JDBC are mocked - what is under test is the decision
 * logic, not Postgres or the endpoint.
 */
class MutableLlmSettingsServiceTest {

    private static final LlmDefaults DEFAULTS = new LlmDefaults(
            "http://env:13305/api/v1", "none", "env-chat", "env-embed", 2560,
            Duration.ofMinutes(10), 1, 0.1, 4096);

    private LlmSettingsRepository repository;
    private LlmClientFactory clientFactory;
    private JdbcTemplate jdbcTemplate;
    private MutableLlmSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(LlmSettingsRepository.class);
        clientFactory = mock(LlmClientFactory.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new MutableLlmSettingsService(repository, clientFactory, jdbcTemplate, DEFAULTS);
        lenient().when(clientFactory.chatModel(any()))
                .thenAnswer(invocation -> mock(ChatModel.class));
        lenient().when(clientFactory.embeddingModel(any()))
                .thenAnswer(invocation -> embedModelOfDim(1024));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static EmbeddingModel embedModelOfDim(int dimension) {
        EmbeddingModel model = mock(EmbeddingModel.class);
        lenient().when(model.embed(any(String.class))).thenReturn(new float[dimension]);
        return model;
    }

    private static LlmSettings row(String embedModel, int dimension, String chatApiKey,
            String embedApiKey) {
        return LlmSettings.builder()
                .id(1L)
                .baseUrl("http://db:9000/v1")
                .chatModel("db-chat")
                .embedModel(embedModel)
                .embedDimension(dimension)
                .chatApiKey(chatApiKey)
                .embedApiKey(embedApiKey)
                .updatedAt(Instant.parse("2026-08-31T10:00:00Z"))
                .updatedBy("admin")
                .build();
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("with no database row the environment configuration applies")
    void environmentIsTheFallback() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        EffectiveLlmSettings settings = service.effective();

        assertThat(settings.source()).isEqualTo("environment");
        assertThat(settings.baseUrl()).isEqualTo("http://env:13305/api/v1");
        assertThat(settings.chatModel()).isEqualTo("env-chat");
        assertThat(settings.embedModel()).isEqualTo("env-embed");
        assertThat(settings.embedDimension()).isEqualTo(2560);
        assertThat(settings.updatedAt()).isNull();
        assertThat(settings.updatedBy()).isNull();
        // The documented "none" placeholder is not a credential.
        assertThat(settings.chatApiKeySet()).isFalse();
        assertThat(settings.embedApiKeySet()).isFalse();
    }

    @Test
    @DisplayName("a database row overrides the environment, including its keys and audit fields")
    void databaseRowOverrides() {
        when(repository.findById(1L))
                .thenReturn(Optional.of(row("db-embed", 1024, "sk-chat", "sk-embed")));

        EffectiveLlmSettings settings = service.effective();

        assertThat(settings.source()).isEqualTo("database");
        assertThat(settings.baseUrl()).isEqualTo("http://db:9000/v1");
        assertThat(settings.chatModel()).isEqualTo("db-chat");
        assertThat(settings.embedModel()).isEqualTo("db-embed");
        assertThat(settings.embedDimension()).isEqualTo(1024);
        assertThat(settings.chatApiKey()).isEqualTo("sk-chat");
        assertThat(settings.embedApiKey()).isEqualTo("sk-embed");
        assertThat(settings.chatApiKeySet()).isTrue();
        assertThat(settings.embedApiKeySet()).isTrue();
        assertThat(settings.updatedBy()).isEqualTo("admin");
        assertThat(settings.updatedAt()).isEqualTo(Instant.parse("2026-08-31T10:00:00Z"));
    }

    @Test
    @DisplayName("a database row with an empty key means explicitly no key, not the env fallback")
    void emptyStoredKeyMeansNoKey() {
        when(repository.findById(1L)).thenReturn(Optional.of(row("db-embed", 1024, "", null)));

        EffectiveLlmSettings settings = service.effective();

        assertThat(settings.chatApiKey()).isEmpty();
        assertThat(settings.chatApiKeySet()).isFalse();
        // A null column (migrated by V8) reads the same as empty.
        assertThat(settings.embedApiKeySet()).isFalse();
    }

    // ------------------------------------------------------------------
    // Delegate caching
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the delegate is cached while the configuration is unchanged, rebuilt when it is")
    void delegatesAreRebuiltOnChange() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        ChatModel first = service.chatModel();
        assertThat(service.chatModel()).as("unchanged configuration reuses the delegate")
                .isSameAs(first);

        when(repository.findById(1L)).thenReturn(Optional.of(row("db-embed", 1024, null, null)));
        ChatModel second = service.chatModel();

        assertThat(second).as("a changed configuration builds a new delegate")
                .isNotSameAs(first);
        assertThat(service.chatModel()).isSameAs(second);
    }

    // ------------------------------------------------------------------
    // Updating
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a chat-model change saves without confirmation, probe or DDL")
    void nonEmbeddingChangeSavesDirectly() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        MutableLlmSettingsService.UpdateOutcome outcome = service.update(
                new MutableLlmSettingsService.UpdateCommand(
                        "http://new:8000/v1", "new-chat", "env-embed", null, null, false),
                "admin");

        assertThat(outcome.embeddingModelChanged()).isFalse();
        assertThat(outcome.settings().embedDimension()).isEqualTo(2560);
        assertThat(outcome.settings().source()).isEqualTo("database");
        verify(clientFactory, never()).embeddingModel(any());
        verify(jdbcTemplate, never()).execute(any(String.class));
    }

    @Test
    @DisplayName("per key: omitted keeps the current one, empty means no key, a value sets it")
    void apiKeyKeepClearAndSet() {
        when(repository.findById(1L))
                .thenReturn(Optional.of(row("env-embed", 2560, "sk-chat", "sk-embed")));

        MutableLlmSettingsService.UpdateOutcome kept = service.update(
                new MutableLlmSettingsService.UpdateCommand(
                        "http://db:9000/v1", "db-chat", "env-embed", null, null, false),
                "admin");
        assertThat(kept.settings().chatApiKey()).isEqualTo("sk-chat");
        assertThat(kept.settings().embedApiKey()).isEqualTo("sk-embed");

        // Each key is independent: clear the chat key, set a new embed key.
        MutableLlmSettingsService.UpdateOutcome cleared = service.update(
                new MutableLlmSettingsService.UpdateCommand(
                        "http://db:9000/v1", "db-chat", "env-embed", "  ", "sk-embed-2", false),
                "admin");
        assertThat(cleared.settings().chatApiKey()).isEmpty();
        assertThat(cleared.settings().chatApiKeySet()).isFalse();
        assertThat(cleared.settings().embedApiKey()).isEqualTo("sk-embed-2");
    }

    @Test
    @DisplayName("an embedding-model change without confirmReembed is refused and saves nothing")
    void embedModelChangeRequiresConfirmation() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                new MutableLlmSettingsService.UpdateCommand(
                        "http://env:13305/api/v1", "env-chat", "other-embed", null, null, false),
                "admin"))
                .isInstanceOf(ReembedConfirmationRequiredException.class)
                .hasMessageContaining("env-embed")
                .hasMessageContaining("other-embed");

        verify(repository, never()).saveAndFlush(any());
        verify(jdbcTemplate, never()).execute(any(String.class));
    }

    @Test
    @DisplayName("a confirmed embedding-model change probes the dimension, alters the column and saves")
    void confirmedEmbedModelChangeAltersTheColumn() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        MutableLlmSettingsService.UpdateOutcome outcome = service.update(
                new MutableLlmSettingsService.UpdateCommand(
                        "http://env:13305/api/v1", "env-chat", "other-embed", null, null, true),
                "admin");

        assertThat(outcome.embeddingModelChanged()).isTrue();
        // The probe's 1024-float vector set the recorded dimension...
        assertThat(outcome.settings().embedDimension()).isEqualTo(1024);
        // ...and the column was dropped and re-added at that size, not converted.
        verify(jdbcTemplate).execute("ALTER TABLE document_chunks DROP COLUMN embedding");
        verify(jdbcTemplate).execute("ALTER TABLE document_chunks ADD COLUMN embedding vector(1024)");
        verify(repository).saveAndFlush(any());
    }

    @Test
    @DisplayName("a probe failure refuses the save before any DDL runs")
    void probeFailureAbortsTheChange() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        EmbeddingModel broken = mock(EmbeddingModel.class);
        when(broken.embed(any(String.class))).thenThrow(new IllegalStateException("connection refused"));
        when(clientFactory.embeddingModel(any())).thenReturn(broken);

        assertThatThrownBy(() -> service.update(
                new MutableLlmSettingsService.UpdateCommand(
                        "http://env:13305/api/v1", "env-chat", "other-embed", null, null, true),
                "admin"))
                .isInstanceOf(LlmEndpointException.class)
                .hasMessageContaining("other-embed");

        verify(jdbcTemplate, never()).execute(any(String.class));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a DDL failure is reported as a settings fault, naming the needed permission")
    void ddlFailureSurfacesClearly() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException(
                        "permission denied for table document_chunks"))
                .when(jdbcTemplate).execute(any(String.class));

        assertThatThrownBy(() -> service.update(
                new MutableLlmSettingsService.UpdateCommand(
                        "http://env:13305/api/v1", "env-chat", "other-embed", null, null, true),
                "admin"))
                .isInstanceOf(LlmSettingsException.class)
                .hasMessageContaining("vector(1024)")
                .hasMessageContaining("permission denied");

        verify(repository, never()).saveAndFlush(any());
    }
}
