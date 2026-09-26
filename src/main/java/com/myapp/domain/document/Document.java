package com.myapp.domain.document;

import java.util.Map;

public record Document(String id, String title, String content, Map<String, Object> metadata) {
    public Document {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        title = title == null ? "" : title;
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
