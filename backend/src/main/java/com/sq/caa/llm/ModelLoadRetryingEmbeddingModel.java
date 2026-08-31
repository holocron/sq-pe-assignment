package com.sq.caa.llm;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * The embedding counterpart of {@link ModelLoadRetryingChatModel}: one load-and-retry on LM
 * Studio's "not loaded" signature, for the RAG ingest/search path and the dimension probe.
 */
public class ModelLoadRetryingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final LmStudioModelLoader loader;

    public ModelLoadRetryingEmbeddingModel(EmbeddingModel delegate, LmStudioModelLoader loader) {
        this.delegate = delegate;
        this.loader = loader;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return loader.callWithLoadRetry(() -> delegate.call(request));
    }

    @Override
    public float[] embed(Document document) {
        return loader.callWithLoadRetry(() -> delegate.embed(document));
    }

    @Override
    public int dimensions() {
        // A local constant lookup - no network call, so no retry.
        return delegate.dimensions();
    }
}
