package com.sq.caa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.errors.OpenAIServiceException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link LmStudioModelLoader}: the narrow "not loaded" matcher, and the load-and-retry-once
 * flow against a stub HTTP endpoint (JDK {@link HttpServer}).
 */
class LmStudioModelLoaderTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger loadCalls = new AtomicInteger();
    private volatile int loadStatus = 200;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/models/load", exchange -> {
            loadCalls.incrementAndGet();
            byte[] body = ("{\"status\":\"loaded\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(loadStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private LmStudioModelLoader loader() {
        return new LmStudioModelLoader(baseUrl, "some-model", Duration.ofSeconds(30));
    }

    private static RuntimeException lmStudioNotLoaded() {
        OpenAIServiceException e = mock(OpenAIServiceException.class);
        when(e.statusCode()).thenReturn(400);
        when(e.getMessage()).thenReturn(
                "400: Failed to load model \"some-model\". Error: Failed to load model.");
        return e;
    }

    /* ------------------------------------------------------------------ */
    /* The matcher                                                         */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("matches LM Studio's 400 'Failed to load model', also nested in a wrapper")
    void matchesNotLoadedSignature() {
        assertThat(LmStudioModelLoader.isModelNotLoaded(lmStudioNotLoaded())).isTrue();
        assertThat(LmStudioModelLoader.isModelNotLoaded(
                new RuntimeException("wrapped", lmStudioNotLoaded()))).isTrue();
    }

    @Test
    @DisplayName("other 400s, other statuses and non-OpenAI errors do NOT match")
    void rejectsOtherErrors() {
        OpenAIServiceException other400 = mock(OpenAIServiceException.class);
        when(other400.statusCode()).thenReturn(400);
        when(other400.getMessage()).thenReturn("400: Invalid request: max_tokens too large");
        OpenAIServiceException server500 = mock(OpenAIServiceException.class);
        when(server500.statusCode()).thenReturn(500);
        when(server500.getMessage()).thenReturn("500: Failed to load model \"x\"");
        assertThat(LmStudioModelLoader.isModelNotLoaded(other400)).isFalse();
        assertThat(LmStudioModelLoader.isModelNotLoaded(server500)).isFalse();
        assertThat(LmStudioModelLoader.isModelNotLoaded(
                new IllegalStateException("Failed to load model"))).isFalse();
        assertThat(LmStudioModelLoader.isModelNotLoaded(new RuntimeException("boom"))).isFalse();
    }

    /* ------------------------------------------------------------------ */
    /* Load and retry                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("loads the model and retries once: the retried call's answer is returned")
    void loadsThenRetries() {
        AtomicInteger attempts = new AtomicInteger();
        String answer = loader().callWithLoadRetry(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw lmStudioNotLoaded();
            }
            return "ok";
        });
        assertThat(answer).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
        assertThat(loadCalls).hasValue(1);
    }

    @Test
    @DisplayName("a non-matching error passes straight through, no load attempted")
    void otherErrorsPassThrough() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> loader().callWithLoadRetry(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(attempts).hasValue(1);
        assertThat(loadCalls).hasValue(0);
    }

    @Test
    @DisplayName("404 from the load endpoint (not LM Studio) fails with an actionable message")
    void unsupportedEndpointFailsHonestly() {
        loadStatus = 404;
        assertThatThrownBy(() -> loader().callWithLoadRetry(() -> {
            throw lmStudioNotLoaded();
        }))
                .isInstanceOf(LlmEndpointException.class)
                .hasMessageContaining("some-model")
                .hasMessageContaining("does not support on-demand loading");
        assertThat(loadCalls).hasValue(1);
    }

    @Test
    @DisplayName("a failed load attempt fails honestly")
    void failedLoadFailsHonestly() {
        loadStatus = 500;
        assertThatThrownBy(() -> loader().callWithLoadRetry(() -> {
            throw lmStudioNotLoaded();
        }))
                .isInstanceOf(LlmEndpointException.class)
                .hasMessageContaining("the load attempt failed with HTTP 500");
    }

    @Test
    @DisplayName("a call still failing after a successful load fails honestly")
    void retryFailureFailsHonestly() {
        assertThatThrownBy(() -> loader().callWithLoadRetry(() -> {
            throw lmStudioNotLoaded();
        }))
                .isInstanceOf(LlmEndpointException.class)
                .hasMessageContaining("was loaded on demand but the call still failed");
    }

    @Test
    @DisplayName("the streaming variant retries once on the not-loaded signature")
    void streamRetriesOnce() {
        AtomicInteger attempts = new AtomicInteger();
        String answer = loader().streamWithLoadRetry(() -> {
            if (attempts.incrementAndGet() == 1) {
                return reactor.core.publisher.Flux.error(lmStudioNotLoaded());
            }
            return reactor.core.publisher.Flux.just("ok");
        }).blockLast();
        assertThat(answer).isEqualTo("ok");
        assertThat(loadCalls).hasValue(1);
    }

    @Test
    @DisplayName("originOf strips the /v1 path")
    void originStripsPath() {
        assertThat(LmStudioModelLoader.originOf("http://localhost:1234/v1"))
                .isEqualTo("http://localhost:1234");
    }

    /**
     * End-to-end through the real factory and OpenAI client: a stub that answers the first chat
     * completion with LM Studio's 400 "Failed to load model" body, accepts the load, and answers
     * the retry. Proves the decorator the factory builds drives the whole sequence over HTTP.
     */
    @Test
    @DisplayName("factory-built chat model loads and retries against an LM Studio stub")
    void factoryBuiltModelLoadsAndRetries() throws IOException {
        AtomicInteger chatCalls = new AtomicInteger();
        HttpServer lmStudio = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        lmStudio.createContext("/v1/chat/completions", exchange -> {
            boolean first = chatCalls.incrementAndGet() == 1;
            int status = first ? 400 : 200;
            String json = first
                    ? "{\"error\":{\"message\":\"Failed to load model \\\"some-model\\\". Error:"
                            + " Failed to load model.\",\"type\":\"invalid_request_error\"}}"
                    : "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,"
                            + "\"model\":\"some-model\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                            + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        AtomicInteger loads = new AtomicInteger();
        lmStudio.createContext("/api/v1/models/load", exchange -> {
            loads.incrementAndGet();
            byte[] body = "{\"status\":\"loaded\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        lmStudio.start();
        try {
            LlmDefaults defaults = new LlmDefaults(
                    "http://127.0.0.1:" + lmStudio.getAddress().getPort() + "/v1", "",
                    "some-model", "embed-model", 2560, Duration.ofMinutes(5), 0, 0.1, 100);
            ChatModel model = new OpenAiLlmClientFactory(defaults).chatModel(
                    new EffectiveLlmSettings(defaults.baseUrl(), "some-model", "embed-model", 2560,
                            "", "", "test", null, null));
            ChatResponse response = model.call(new Prompt("say ok"));
            assertThat(response.getResult().getOutput().getText()).isEqualTo("ok");
            assertThat(chatCalls).hasValue(2);
            assertThat(loads).hasValue(1);
        } finally {
            lmStudio.stop(0);
        }
    }
}
