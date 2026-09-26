package com.myapp.infrastructure.vector.qdrant;

import com.myapp.domain.vector.VectorDocument;
import com.myapp.domain.vector.DistanceMetric;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import com.myapp.infrastructure.vector.http.JsonHttpClient;
import com.myapp.infrastructure.vector.http.VectorHttpSupport;
import com.myapp.port.VectorStore;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QdrantVectorStore implements VectorStore {
    private final JsonHttpClient client;
    private final QdrantProperties properties;

    public QdrantVectorStore(JsonHttpClient client, QdrantProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String database() { return "qdrant"; }

    @Override
    public int dimension() { return properties.getDimension(); }

    @Override
    public DistanceMetric metric() { return properties.getMetric(); }

    @Override
    public void upsert(List<VectorDocument> documents) {
        if (documents.isEmpty()) return;
        VectorHttpSupport.validateDimensions(documents, properties.getDimension());
        List<Map<String, Object>> points = documents.stream().map(document -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", document.id());
            payload.put("documentId", document.documentId());
            payload.put("chunkId", document.chunkId());
            payload.put("content", document.content());
            payload.put("metadata", document.metadata());
            return Map.<String, Object>of(
                    "id", VectorHttpSupport.uuid(document.id()),
                    "vector", VectorHttpSupport.floats(document.embedding()),
                    "payload", payload);
        }).toList();
        client.put("/collections/" + properties.getCollection() + "/points?wait=true", Map.of("points", points));
    }

    @Override
    public List<VectorSearchResult> search(VectorSearchRequest request) {
        if (request.queryVector().length != properties.getDimension()) throw new IllegalArgumentException("Unexpected query vector dimension");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", VectorHttpSupport.floats(request.queryVector()));
        body.put("limit", request.topK());
        // Match the response contract of the other adapters; stored content/metadata is not part of search timing.
        body.put("with_payload", List.of("id", "documentId", "chunkId"));
        body.put("with_vector", false);
        body.put("params", Map.of("hnsw_ef", request.intParameter("hnsw_ef", properties.getDefaultEfSearch()), "exact", false));
        if (!request.filter().isEmpty()) {
            List<Map<String, Object>> must = request.filter().equals().entrySet().stream()
                    .map(entry -> Map.<String, Object>of("key", "metadata." + entry.getKey(), "match", Map.of("value", entry.getValue())))
                    .toList();
            body.put("filter", Map.of("must", must));
        }
        JsonNode response = client.post("/collections/" + properties.getCollection() + "/points/query", body);
        JsonNode points = response.get("result") == null ? null : response.get("result").get("points");
        if (points == null || !points.isArray()) return List.of();
        List<VectorSearchResult> results = new ArrayList<>();
        for (JsonNode point : points) {
            JsonNode payload = point.get("payload");
            results.add(new VectorSearchResult(
                    payload.get("id").asString(), payload.get("documentId").asString(), payload.get("chunkId").asString(),
                    properties.getMetric() == DistanceMetric.EUCLIDEAN
                            ? -point.get("score").asDouble() : point.get("score").asDouble()));
        }
        return results;
    }

    @Override
    public void deleteAll() {
        client.post("/collections/" + properties.getCollection() + "/points/delete?wait=true",
                Map.of("filter", Map.of("must", List.of())));
    }

    @Override
    public long count() {
        JsonNode response = client.post("/collections/" + properties.getCollection() + "/points/count", Map.of("exact", true));
        JsonNode result = response.get("result");
        return result == null || result.get("count") == null ? 0 : result.get("count").asLong();
    }
}
