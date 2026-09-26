package com.myapp.application.embedding;

import com.myapp.port.EmbeddingProvider;

public class EmbeddingService {
    private final EmbeddingProvider embeddingProvider;

    public EmbeddingService(EmbeddingProvider embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public float[] embed(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        float[] vector = embeddingProvider.embed(text);
        if (vector.length != embeddingProvider.dimension()) {
            throw new IllegalStateException("Embedding provider returned an unexpected dimension");
        }
        return vector;
    }
}
