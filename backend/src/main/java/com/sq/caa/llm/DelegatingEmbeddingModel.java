package com.sq.caa.llm;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * The application's {@code EmbeddingModel} bean: forwards every call to the model built for the
 * configuration in effect at call time. The auto-configured {@code PgVectorStore} takes this
 * instance at construction and therefore follows settings changes without being rebuilt; each
 * individual {@code add}/{@code similaritySearch} resolves the delegate once and completes on it.
 */
public class DelegatingEmbeddingModel implements EmbeddingModel {

    private final MutableLlmSettingsService settingsService;

    public DelegatingEmbeddingModel(MutableLlmSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return settingsService.embeddingModel().call(request);
    }

    @Override
    public float[] embed(Document document) {
        return settingsService.embeddingModel().embed(document);
    }

    @Override
    public int dimensions() {
        return settingsService.embeddingModel().dimensions();
    }
}
