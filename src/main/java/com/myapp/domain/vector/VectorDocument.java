package com.myapp.domain.vector;

import java.util.Map;
import java.util.Objects;

public record VectorDocument(
        String id,
        String documentId,
        String chunkId,
        String content,
        float[] embedding,
        Map<String, Object> metadata
) {
    public VectorDocument {
        id = requireText(id, "id");
        documentId = requireText(documentId, "documentId");
        chunkId = requireText(chunkId, "chunkId");
        content = Objects.requireNonNullElse(content, "");
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding must not be empty");
        }
        embedding = embedding.clone();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public VectorDocument(String id, String documentId, String chunkId, String content, float[] embedding) {
        this(id, documentId, chunkId, content, embedding, Map.of());
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
