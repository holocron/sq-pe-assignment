package com.sq.caa.llm;

import com.openai.errors.OpenAIServiceException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Best-effort, LM Studio-specific "model not loaded" recovery.
 *
 * <p>LM Studio answers a call for a model that is not loaded with {@code 400 "Failed to load
 * model \"<id>\"…"} (no JIT loading unless the user enabled it server-side). The OpenAI standard
 * has no load concept and other endpoints (the lemonade router, real OpenAI) manage loading
 * themselves, so this kicks in ONLY on that exact signature: it posts the model id to LM Studio's
 * {@code POST {origin}/api/v1/models/load} (origin = scheme://host:port of the configured base
 * URL, without the {@code /v1} path), waits for the load (bounded by the configured LLM timeout -
 * loading a large model takes minutes), then retries the original call ONCE. A 404 from the load
 * endpoint means the server is not LM Studio; the call then fails with an actionable message
 * instead of the raw 400.
 */
public final class LmStudioModelLoader {

    private static final Logger log = LoggerFactory.getLogger(LmStudioModelLoader.class);

    /** LM Studio's signature for a call against a model that is not loaded. */
    private static final String NOT_LOADED_SIGNATURE = "Failed to load model";

    private final String baseUrl;
    private final String modelId;
    private final Duration loadTimeout;

    public LmStudioModelLoader(String baseUrl, String modelId, Duration loadTimeout) {
        this.baseUrl = baseUrl;
        this.modelId = modelId;
        this.loadTimeout = loadTimeout;
    }

    /** Runs the call, with one load-and-retry on LM Studio's not-loaded signature. */
    public <T> T callWithLoadRetry(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            if (!isModelNotLoaded(e)) {
                throw e;
            }
            loadOrThrow(e);
            try {
                return call.get();
            } catch (RuntimeException retryFailure) {
                log.warn("Model '{}' still failing after a successful load", modelId, retryFailure);
                throw new LlmEndpointException("The model '" + modelId + "' at " + baseUrl
                        + " was loaded on demand but the call still failed: "
                        + rootMessage(retryFailure));
            }
        }
    }

    /** The streaming variant: errors surface inside the flux, so the retry wraps subscription. */
    public <T> Flux<T> streamWithLoadRetry(Supplier<Flux<T>> call) {
        return Flux.defer(call).onErrorResume(e -> {
            if (!isModelNotLoaded(e)) {
                return Flux.error(e);
            }
            loadOrThrow(e instanceof RuntimeException re ? re : new RuntimeException(e));
            return Flux.defer(call).onErrorResume(retryFailure -> Flux.error(
                    new LlmEndpointException("The model '" + modelId + "' at " + baseUrl
                            + " was loaded on demand but the call still failed: "
                            + rootMessage(retryFailure))));
        });
    }

    /** True only for LM Studio's 400 "Failed to load model" - every other error passes through. */
    static boolean isModelNotLoaded(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof OpenAIServiceException serviceError
                    && serviceError.statusCode() == 400
                    && cursor.getMessage() != null
                    && cursor.getMessage().contains(NOT_LOADED_SIGNATURE)) {
                return true;
            }
        }
        return false;
    }

    /** Loads the model, or throws the honest, actionable error when loading is impossible. */
    private void loadOrThrow(RuntimeException original) {
        String origin = originOf(baseUrl);
        String url = origin + "/api/v1/models/load";
        log.info("Model '{}' is not loaded on {}; asking LM Studio to load it (bound: {})",
                modelId, origin, loadTimeout);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(loadTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"" + modelId + "\"}"))
                .build();
        int status;
        String body;
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            body = response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw notLoaded("the load request was interrupted", original);
        } catch (Exception e) {
            throw notLoaded("the load request could not be sent (" + e.getMessage() + ")", original);
        }
        if (status == 404) {
            throw notLoaded("the endpoint does not support on-demand loading (it answered 404 to "
                    + "LM Studio's load API - load the model on the server side, or pick a model "
                    + "that is already loaded)", original);
        }
        if (status != 200) {
            throw notLoaded("the load attempt failed with HTTP " + status + ": " + body, original);
        }
        log.info("Model '{}' loaded on {}: {}", modelId, origin, body);
    }

    private LlmEndpointException notLoaded(String why, RuntimeException original) {
        // The original error goes to the log, not the cause chain: probes and the re-embed job
        // surface the ROOT cause's message, and that root must be this actionable sentence.
        log.warn("Model '{}' at {} is not loaded: {}", modelId, baseUrl, why, original);
        return new LlmEndpointException("The model '" + modelId + "' at " + baseUrl
                + " is not loaded, and " + why + ".");
    }

    /** scheme://host:port of the configured base URL (its /v1 path is OpenAI-only). */
    static String originOf(String baseUrl) {
        URI uri = URI.create(baseUrl);
        return uri.getScheme() + "://" + uri.getAuthority();
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
