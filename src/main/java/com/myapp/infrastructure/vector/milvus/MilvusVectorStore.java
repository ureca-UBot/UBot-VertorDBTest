package com.myapp.infrastructure.vector.milvus;

import com.myapp.domain.vector.DistanceMetric;
import com.myapp.domain.vector.VectorDocument;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import com.myapp.infrastructure.vector.http.JsonHttpClient;
import com.myapp.infrastructure.vector.http.VectorHttpSupport;
import com.myapp.port.VectorStore;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MilvusVectorStore implements VectorStore {
    private final JsonHttpClient client;
    private final MilvusProperties properties;

    public MilvusVectorStore(JsonHttpClient client, MilvusProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String database() { return "milvus"; }

    @Override
    public int dimension() { return properties.getDimension(); }

    @Override
    public DistanceMetric metric() { return properties.getMetric(); }

    @Override
    public void upsert(List<VectorDocument> documents) {
        if (documents.isEmpty()) return;
        VectorHttpSupport.validateDimensions(documents, properties.getDimension());
        List<Map<String, Object>> data = documents.stream().map(document -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", document.id());
            row.put("document_id", document.documentId());
            row.put("chunk_id", document.chunkId());
            row.put("content", document.content());
            row.put("metadata", document.metadata());
            row.put("embedding", VectorHttpSupport.floats(document.embedding()));
            return row;
        }).toList();
        MilvusIndexManager.requireSuccess(client.post("/v2/vectordb/entities/upsert", Map.of(
                "dbName", properties.getDatabase(), "collectionName", properties.getCollection(), "data", data)));
    }

    @Override
    public List<VectorSearchResult> search(VectorSearchRequest request) {
        if (request.queryVector().length != properties.getDimension()) throw new IllegalArgumentException("Unexpected query vector dimension");
        String searchParameter = searchParameterName();
        int searchValue = request.intParameter(searchParameter, defaultSearchValue());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dbName", properties.getDatabase());
        body.put("collectionName", properties.getCollection());
        body.put("data", List.of(VectorHttpSupport.floats(request.queryVector())));
        body.put("annsField", "embedding");
        body.put("limit", request.topK());
        body.put("outputFields", List.of("id", "document_id", "chunk_id"));
        body.put("searchParams", Map.of("metricType", metricType(), "params", Map.of(searchParameter, searchValue)));
        if (!request.filter().isEmpty()) body.put("filter", filter(request.filter().equals()));
        JsonNode response = client.post("/v2/vectordb/entities/search", body);
        MilvusIndexManager.requireSuccess(response);
        JsonNode rows = response.get("data");
        if (rows == null || !rows.isArray()) return List.of();
        List<VectorSearchResult> results = new ArrayList<>();
        for (JsonNode hit : rows) {
            JsonNode row = hit.get("entity") == null ? hit : hit.get("entity");
            double distance = hit.get("distance") == null ? hit.get("score").asDouble() : hit.get("distance").asDouble();
            double score = properties.getMetric() == DistanceMetric.EUCLIDEAN ? -distance : distance;
            results.add(new VectorSearchResult(row.get("id").asString(), row.get("document_id").asString(), row.get("chunk_id").asString(), score));
        }
        return results;
    }

    private String filter(Map<String, Object> values) {
        return values.entrySet().stream()
                .map(entry -> "metadata[\"" + escape(entry.getKey()) + "\"] == " + literal(entry.getValue()))
                .reduce((left, right) -> left + " and " + right).orElse("");
    }

    private String literal(Object value) {
        if (value instanceof Number || value instanceof Boolean) return value.toString().toLowerCase(Locale.ROOT);
        return "\"" + escape(value.toString()) + "\"";
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void deleteAll() {
        MilvusIndexManager.requireSuccess(client.post("/v2/vectordb/entities/delete", Map.of(
                "dbName", properties.getDatabase(), "collectionName", properties.getCollection(), "filter", "id != \"\"")));
    }

    @Override
    public long count() {
        JsonNode response = client.post("/v2/vectordb/entities/query", Map.of(
                "dbName", properties.getDatabase(), "collectionName", properties.getCollection(),
                "filter", "id != \"\"", "outputFields", List.of("count(*)")));
        MilvusIndexManager.requireSuccess(response);
        JsonNode data = response.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) return 0;
        JsonNode count = data.get(0).get("count(*)");
        return count == null ? 0 : count.asLong();
    }

    private String metricType() {
        return switch (properties.getMetric()) {
            case COSINE -> "COSINE";
            case DOT -> "IP";
            case EUCLIDEAN -> "L2";
        };
    }

    private String searchParameterName() {
        if (properties.getIndexType().equals("HNSW")) return "ef";
        if (properties.getIndexType().equals("DISKANN")) return "search_list";
        return "nprobe";
    }

    private int defaultSearchValue() {
        if (properties.getIndexType().startsWith("IVF_")) return Math.min(8, properties.getIvfNlist());
        return properties.getDefaultEfSearch();
    }
}
