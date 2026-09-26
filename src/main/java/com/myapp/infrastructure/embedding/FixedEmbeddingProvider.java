package com.myapp.infrastructure.embedding;

import com.myapp.port.EmbeddingProvider;

import java.util.Map;

/** Looks up vectors generated before the benchmark; it never calls an embedding API. */
public class FixedEmbeddingProvider implements EmbeddingProvider {
    private final int dimension;
    private final Map<String, float[]> embeddings;

    public FixedEmbeddingProvider(int dimension, Map<String, float[]> embeddings) {
        this.dimension = dimension;
        this.embeddings = Map.copyOf(embeddings);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = embeddings.get(text);
        if (vector == null) throw new IllegalArgumentException("No precomputed embedding for the supplied text");
        return vector.clone();
    }
}
