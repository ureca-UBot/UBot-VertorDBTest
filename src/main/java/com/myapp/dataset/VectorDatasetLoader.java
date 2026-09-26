package com.myapp.dataset;

import com.myapp.domain.vector.VectorDocument;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class VectorDatasetLoader {
    private final ObjectMapper objectMapper;

    public VectorDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<VectorDocument> load(Path path) {
        List<VectorDocument> documents = JsonlSupport.readMapped(path, objectMapper, this::toDocument);
        if (documents.isEmpty()) throw new IllegalStateException("Vector dataset is empty: " + path);
        int dimension = documents.getFirst().embedding().length;
        boolean invalidDimension = documents.stream().anyMatch(document -> document.embedding().length != dimension);
        if (invalidDimension) throw new IllegalArgumentException("All document vectors must use the same dimension");
        Set<String> ids = new HashSet<>();
        Set<String> chunkIds = new HashSet<>();
        for (VectorDocument document : documents) {
            if (!ids.add(document.id())) throw new IllegalArgumentException("Duplicate document vector id: " + document.id());
            if (!chunkIds.add(document.chunkId())) throw new IllegalArgumentException("Duplicate chunkId: " + document.chunkId());
        }
        return documents;
    }

    private VectorDocument toDocument(JsonNode node) {
        String id = JsonlSupport.text(node, "id", "chunkId", "chunk_id");
        String documentId = JsonlSupport.text(node, "documentId", "document_id");
        String chunkId = JsonlSupport.text(node, "chunkId", "chunk_id", "id");
        return new VectorDocument(
                id,
                documentId == null ? id : documentId,
                chunkId == null ? id : chunkId,
                JsonlSupport.text(node, "content", "text"),
                JsonlSupport.vector(node, "embedding", "vector"),
                JsonlSupport.object(node, objectMapper, "metadata")
        );
    }
}
