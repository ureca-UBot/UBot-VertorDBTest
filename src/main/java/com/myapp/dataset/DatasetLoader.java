package com.myapp.dataset;

import com.myapp.domain.document.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

public class DatasetLoader {
    private final ObjectMapper objectMapper;

    public DatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Document> load(Path path) {
        return JsonlSupport.readMapped(path, objectMapper, this::toDocument);
    }

    private Document toDocument(JsonNode node) {
        return new Document(
                JsonlSupport.text(node, "id", "documentId", "document_id"),
                JsonlSupport.text(node, "title"),
                JsonlSupport.text(node, "content", "text"),
                JsonlSupport.object(node, objectMapper, "metadata")
        );
    }
}
