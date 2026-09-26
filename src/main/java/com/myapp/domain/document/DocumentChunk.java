package com.myapp.domain.document;

import java.util.Map;

public record DocumentChunk(String id, String documentId, int sequence, String content, Map<String, Object> metadata) {
    public DocumentChunk {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
