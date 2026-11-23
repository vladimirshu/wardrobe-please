package com.ecomassistant.search;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import java.time.Duration;

public final class SearchEmbeddingModel implements EmbeddingModel {
    private final EmbeddingModel delegate;
    public SearchEmbeddingModel(EmbeddingModel delegate) { this.delegate = delegate; }
    @Override public EmbeddingResponse call(EmbeddingRequest request) {
        var budget = SearchBudget.current();
        if (budget == null) return delegate.call(request);
        return budget.call(() -> delegate.call(new EmbeddingRequest(request.getInstructions(),
                OpenAiEmbeddingOptions.builder().merge(request.getOptions()).maxRetries(0)
                        .timeout(Duration.ofNanos(Math.min(SearchBudget.ATTEMPT.toNanos(), budget.remainingNanos())))
                        .build())), true);
    }
    @Override public float[] embed(Document document) {
        if (SearchBudget.current() == null) return delegate.embed(document);
        return embed(delegate.getEmbeddingContent(document));
    }
    @Override public int dimensions() { return delegate.dimensions(); }
    @Override public String getEmbeddingContent(Document document) { return delegate.getEmbeddingContent(document); }
}
