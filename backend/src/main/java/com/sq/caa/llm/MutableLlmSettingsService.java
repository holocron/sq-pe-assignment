package com.sq.caa.llm;

import com.sq.caa.domain.LlmSettings;
import com.sq.caa.repository.LlmSettingsRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The effective LLM configuration and the live clients built from it.
 *
 * <p>Resolution rule: the single {@code llm_settings} row wins; with no row the environment the
 * application booted with ({@link LlmDefaults}) is the configuration and
 * {@link EffectiveLlmSettings#source()} reports {@code "environment"}.
 *
 * <p><b>Runtime application.</b> The application never injects a concrete {@code OpenAiChatModel}:
 * the {@code chatModel}/{@code embeddingModel} beans are {@link DelegatingChatModel} /
 * {@link DelegatingEmbeddingModel}, which ask this service for the current delegate on every call
 * and forward to it. Delegates are cached per configuration and rebuilt when {@link #update} saves
 * a row, so a settings change takes effect on the <em>next</em> call while an in-flight analysis
 * finishes on the delegate instance it already captured. Thread safety is therefore just a volatile
 * reference plus a synchronized rebuild; there is no lock held across a model call.
 *
 * <p><b>Embedding model changes.</b> Changing {@code embedModel} invalidates every stored vector,
 * so it is refused unless the caller confirmed re-embedding ({@code confirmReembed: true}). When
 * confirmed, the new model's dimension is probed (a one-string embedding), {@code
 * document_chunks.embedding} is altered to it (drop + re-add: the rows are about to be re-embedded
 * anyway), the probed dimension is recorded on the row, and the caller starts
 * {@link ReembedService}.
 */
@Service
public class MutableLlmSettingsService implements LlmSettingsProvider {

    private static final Logger log = LoggerFactory.getLogger(MutableLlmSettingsService.class);

    /** Bound on the {@code GET {baseUrl}/models} proxy. */
    private static final Duration MODELS_TIMEOUT = Duration.ofSeconds(10);

    /** Bound on one probe call of the "test" endpoint - long enough for a slow local server. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    /** The one string embedded to learn a model's dimension. */
    static final String DIMENSION_PROBE_TEXT = "embedding dimension probe";

    private final LlmSettingsRepository repository;
    private final LlmClientFactory clientFactory;
    private final JdbcTemplate jdbcTemplate;
    private final LlmDefaults defaults;
    private final JsonMapper jsonMapper = new JsonMapper();

    /** The configuration and its built clients, swapped atomically. */
    private volatile CacheEntry cache;

    private record CacheEntry(EffectiveLlmSettings settings, ChatModel chatModel,
            EmbeddingModel embeddingModel) {
    }

    /**
     * What a PUT wants to save. Per key, {@code null} keeps the current key, an empty/blank string
     * is an explicit "no key" (local model servers), anything else sets the key.
     */
    public record UpdateCommand(String baseUrl, String chatModel, String embedModel,
            String chatApiKey, String embedApiKey, boolean confirmReembed) {
    }

    /** The saved settings and whether the embedding model (and therefore the corpus) changed. */
    public record UpdateOutcome(EffectiveLlmSettings settings, boolean embeddingModelChanged) {
    }

    /** One side of the {@code POST .../test} answer. */
    public record ChatProbe(boolean ok, String detail) {
    }

    /** The embedding side, which also learns the model's dimension. */
    public record EmbedProbe(boolean ok, String detail, Integer dimension) {
    }

    public record ConnectionTestResult(ChatProbe chat, EmbedProbe embed) {
    }

    public MutableLlmSettingsService(LlmSettingsRepository repository, LlmClientFactory clientFactory,
            JdbcTemplate jdbcTemplate, LlmDefaults defaults) {
        this.repository = repository;
        this.clientFactory = clientFactory;
        this.jdbcTemplate = jdbcTemplate;
        this.defaults = defaults;
    }

    /* ------------------------------------------------------------------ */
    /* Effective configuration                                             */
    /* ------------------------------------------------------------------ */

    @Override
    public EffectiveLlmSettings effective() {
        return repository.findById(LlmSettings.SINGLETON_ID)
                .map(MutableLlmSettingsService::fromRow)
                .orElseGet(this::fromEnvironment);
    }

    private EffectiveLlmSettings fromEnvironment() {
        // The environment has a single OPENAI_API_KEY; it is the boot credential for both models.
        return new EffectiveLlmSettings(defaults.baseUrl(), defaults.chatModel(), defaults.embedModel(),
                defaults.embedDimension(), defaults.apiKey(), defaults.apiKey(),
                EffectiveLlmSettings.SOURCE_ENVIRONMENT, null, null);
    }

    private static EffectiveLlmSettings fromRow(LlmSettings row) {
        return new EffectiveLlmSettings(row.getBaseUrl(), row.getChatModel(), row.getEmbedModel(),
                row.getEmbedDimension(), row.getChatApiKey(), row.getEmbedApiKey(),
                EffectiveLlmSettings.SOURCE_DATABASE, row.getUpdatedAt(), row.getUpdatedBy());
    }

    /* ------------------------------------------------------------------ */
    /* Delegates                                                           */
    /* ------------------------------------------------------------------ */

    /** The chat model for the configuration in effect right now. */
    public ChatModel chatModel() {
        return current().chatModel();
    }

    /** The embedding model for the configuration in effect right now. */
    public EmbeddingModel embeddingModel() {
        return current().embeddingModel();
    }

    private CacheEntry current() {
        EffectiveLlmSettings settings = effective();
        CacheEntry entry = cache;
        if (entry != null && entry.settings().equals(settings)) {
            return entry;
        }
        synchronized (this) {
            entry = cache;
            if (entry == null || !entry.settings().equals(settings)) {
                log.info("Building LLM clients for {} (source={}, embed dimension={})",
                        settings.baseUrl(), settings.source(), settings.embedDimension());
                entry = new CacheEntry(settings, clientFactory.chatModel(settings),
                        clientFactory.embeddingModel(settings));
                cache = entry;
            }
            return entry;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Updating                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Validates and persists a new configuration.
     *
     * @throws ReembedConfirmationRequiredException the embedding model changes and the caller did
     *                                              not confirm re-embedding
     * @throws LlmEndpointException                 the new embedding model could not be probed
     * @throws LlmSettingsException                 the embedding-dimension DDL failed
     */
    public UpdateOutcome update(UpdateCommand command, String updatedBy) {
        EffectiveLlmSettings current = effective();
        String baseUrl = requireText(command.baseUrl(), "baseUrl");
        String chatModel = requireText(command.chatModel(), "chatModel");
        String embedModel = requireText(command.embedModel(), "embedModel");

        boolean embeddingModelChanged = !embedModel.equals(current.embedModel());
        if (embeddingModelChanged && !command.confirmReembed()) {
            throw new ReembedConfirmationRequiredException(current.embedModel(), embedModel);
        }

        String chatApiKey = resolveApiKey(command.chatApiKey(), current.chatApiKey());
        String embedApiKey = resolveApiKey(command.embedApiKey(), current.embedApiKey());
        int embedDimension = current.embedDimension();
        if (embeddingModelChanged) {
            embedDimension = probeDimension(baseUrl, embedModel, embedApiKey);
            alterEmbeddingColumn(embedDimension);
        }

        LlmSettings row = repository.findById(LlmSettings.SINGLETON_ID)
                .orElseGet(LlmSettings::new);
        row.setId(LlmSettings.SINGLETON_ID);
        row.setBaseUrl(baseUrl);
        row.setChatModel(chatModel);
        row.setEmbedModel(embedModel);
        row.setEmbedDimension(embedDimension);
        row.setChatApiKey(chatApiKey);
        row.setEmbedApiKey(embedApiKey);
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(updatedBy);
        LlmSettings saved = repository.saveAndFlush(row);
        log.info("LLM settings updated by {}: baseUrl={} chatModel={} embedModel={} (dimension={})",
                updatedBy, baseUrl, chatModel, embedModel, embedDimension);
        return new UpdateOutcome(fromRow(saved), embeddingModelChanged);
    }

    /**
     * The key that a row should carry: an explicit new key, an empty string for an explicit "no
     * key" (local model servers - it does NOT fall back to the environment key, a row drives the
     * whole configuration), and - when the body omitted the field entirely - whatever the current
     * configuration already resolves to.
     */
    private String resolveApiKey(String requestedKey, String currentKey) {
        if (requestedKey != null) {
            return requestedKey.isBlank() ? "" : requestedKey.strip();
        }
        return currentKey;
    }

    /** One embedding of a fixed probe string; the vector's length is the model's dimension. */
    int probeDimension(String baseUrl, String embedModel, String embedApiKey) {
        EffectiveLlmSettings candidate = new EffectiveLlmSettings(baseUrl, null, embedModel, 0,
                null, embedApiKey, "candidate", null, null);
        EmbeddingModel probe = clientFactory.embeddingModel(candidate);
        float[] vector;
        try {
            vector = probe.embed(DIMENSION_PROBE_TEXT);
        } catch (RuntimeException e) {
            throw new LlmEndpointException("The embedding model '" + embedModel + "' at " + baseUrl
                    + " could not be probed: " + rootMessage(e), e);
        }
        if (vector == null || vector.length == 0) {
            throw new LlmEndpointException("The embedding model '" + embedModel + "' at " + baseUrl
                    + " answered the probe with an empty vector.");
        }
        log.info("Embedding model '{}' probed at {} dimensions", embedModel, vector.length);
        return vector.length;
    }

    /**
     * Repoints {@code document_chunks.embedding} at a new vector length. Every stored vector is
     * invalid under the new model and the re-embed job is about to rewrite them, so the column is
     * dropped and re-added rather than converted row by row.
     */
    void alterEmbeddingColumn(int dimension) {
        try {
            jdbcTemplate.execute("ALTER TABLE document_chunks DROP COLUMN embedding");
            jdbcTemplate.execute(
                    "ALTER TABLE document_chunks ADD COLUMN embedding vector(" + dimension + ")");
        } catch (DataAccessException e) {
            throw new LlmSettingsException("document_chunks.embedding could not be changed to "
                    + "vector(" + dimension + "). The application database role must own that "
                    + "table; the settings were NOT saved. Cause: " + rootMessage(e), e);
        }
        log.warn("document_chunks.embedding altered to vector({}); all embeddings must be rebuilt",
                dimension);
    }

    /* ------------------------------------------------------------------ */
    /* Probes (never persist anything)                                     */
    /* ------------------------------------------------------------------ */

    /**
     * A live check of a candidate configuration: one minimal chat call, one embedding probe. Each
     * probe uses its model's key; an omitted key falls back to the stored key for that model, an
     * empty string probes with no key.
     */
    public ConnectionTestResult testConnection(String baseUrl, String chatModel, String embedModel,
            String chatApiKey, String embedApiKey) {
        String url = requireText(baseUrl, "baseUrl");
        EffectiveLlmSettings current = effective();
        EffectiveLlmSettings chatCandidate = new EffectiveLlmSettings(url,
                requireText(chatModel, "chatModel"), null, 0,
                chatApiKey == null ? current.chatApiKey() : chatApiKey, null,
                "candidate", null, null);
        EffectiveLlmSettings embedCandidate = new EffectiveLlmSettings(url, null,
                requireText(embedModel, "embedModel"), 0, null,
                embedApiKey == null ? current.embedApiKey() : embedApiKey,
                "candidate", null, null);
        return new ConnectionTestResult(probeChat(chatCandidate), probeEmbed(embedCandidate));
    }

    private ChatProbe probeChat(EffectiveLlmSettings candidate) {
        try {
            ChatModel model = clientFactory.chatModel(candidate);
            model.call(new Prompt("Reply with the single word: ok",
                    OpenAiChatOptions.builder()
                            .model(candidate.chatModel())
                            .maxTokens(8)
                            .timeout(PROBE_TIMEOUT)
                            .maxRetries(0)
                            .build()));
            return new ChatProbe(true, "Chat model '" + candidate.chatModel() + "' answered.");
        } catch (RuntimeException e) {
            return new ChatProbe(false, rootMessage(e));
        }
    }

    private EmbedProbe probeEmbed(EffectiveLlmSettings candidate) {
        try {
            EmbeddingModel model = clientFactory.embeddingModel(candidate);
            float[] vector = model.embed(DIMENSION_PROBE_TEXT);
            int dimension = vector == null ? 0 : vector.length;
            if (dimension == 0) {
                return new EmbedProbe(false, "The model answered with an empty vector.", null);
            }
            return new EmbedProbe(true,
                    "Embedding model '" + candidate.embedModel() + "' answered.", dimension);
        } catch (RuntimeException e) {
            return new EmbedProbe(false, rootMessage(e), null);
        }
    }

    /**
     * Proxies the endpoint's OpenAI-standard {@code GET {baseUrl}/models}.
     *
     * @param apiKey the endpoint-level credential to send; null means "the currently effective
     *               chat key" (both models normally share the endpoint)
     * @throws LlmEndpointException the endpoint is unreachable or answered non-200
     */
    public List<String> listModels(String baseUrl, String apiKey) {
        String url = stripTrailingSlash(requireText(baseUrl, "baseUrl")) + "/models";
        String key = apiKey == null ? effective().chatApiKey() : apiKey;
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(MODELS_TIMEOUT)
                .GET();
        if (key != null && !key.isBlank()) {
            request.header("Authorization", "Bearer " + key);
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(MODELS_TIMEOUT)
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new LlmEndpointException("The endpoint " + url + " could not be reached: "
                    + rootMessage(e), e);
        }
        if (response.statusCode() != 200) {
            throw new LlmEndpointException("The endpoint " + url + " answered HTTP "
                    + response.statusCode() + " to GET /models.");
        }
        try {
            JsonNode data = jsonMapper.readTree(response.body()).path("data");
            List<String> models = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode entry : data) {
                    JsonNode id = entry.path("id");
                    if (id.isTextual()) {
                        models.add(id.asText());
                    }
                }
            }
            return List.copyOf(models);
        } catch (RuntimeException e) {
            throw new LlmEndpointException("The endpoint " + url
                    + " answered 200 but not with an OpenAI model list: " + rootMessage(e), e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return value.strip();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
