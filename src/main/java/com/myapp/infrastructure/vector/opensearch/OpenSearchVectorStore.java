package com.myapp.infrastructure.vector.opensearch;

import com.myapp.domain.vector.VectorDocument;
import com.myapp.domain.vector.DistanceMetric;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import com.myapp.infrastructure.vector.http.JsonHttpClient;
import com.myapp.infrastructure.vector.http.VectorHttpSupport;
import com.myapp.port.VectorStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenSearchVectorStore implements VectorStore {
    private final JsonHttpClient client;
    private final OpenSearchProperties properties;
    private final ObjectMapper objectMapper;

    public OpenSearchVectorStore(JsonHttpClient client, OpenSearchProperties properties, ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String database() { return "opensearch"; }

    @Override
    public int dimension() { return properties.getDimension(); }

    @Override
    public DistanceMetric metric() { return properties.getMetric(); }

    @Override
    public void upsert(List<VectorDocument> documents) {
        if (documents.isEmpty()) return;
        VectorHttpSupport.validateDimensions(documents, properties.getDimension());
        StringBuilder bulk = new StringBuilder();
        for (VectorDocument document : documents) {
            bulk.append(objectMapper.writeValueAsString(Map.of("index", Map.of("_index", properties.getIndex(), "_id", document.id())))).append('\n');
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("id", document.id());
            source.put("documentId", document.documentId());
            source.put("chunkId", document.chunkId());
            source.put("content", document.content());
            source.put("metadata", document.metadata());
            source.put("embedding", VectorHttpSupport.floats(document.embedding()));
            bulk.append(objectMapper.writeValueAsString(source)).append('\n');
        }
        JsonNode response = client.sendRaw("POST", "/_bulk", bulk.toString(), "application/x-ndjson");
        if (response.get("errors") != null && response.get("errors").asBoolean()) {
            throw new IllegalStateException("OpenSearch bulk request contained failures: " + response);
        }
    }

    @Override
    public List<VectorSearchResult> search(VectorSearchRequest request) {
        if (request.queryVector().length != properties.getDimension()) throw new IllegalArgumentException("Unexpected query vector dimension");
        Map<String, Object> fieldQuery = new LinkedHashMap<>();
        fieldQuery.put("vector", VectorHttpSupport.floats(request.queryVector()));
        int candidateK = request.intParameter("candidate_k", request.topK());
        if (candidateK < request.topK() || candidateK > 10_000) {
            throw new IllegalArgumentException("candidate_k must be between topK and 10000");
        }
        fieldQuery.put("k", candidateK);
        String searchParameter = searchParameterName();
        if (searchParameter != null) {
            int defaultValue = searchParameter.equals("nprobes") ? 1 : properties.getDefaultEfSearch();
            fieldQuery.put("method_parameters", Map.of(searchParameter, request.intParameter(searchParameter, defaultValue)));
        }
        if (!request.filter().isEmpty()) fieldQuery.put("filter", filter(request.filter().equals()));
        Map<String, Object> body = Map.of("size", request.topK(), "_source", List.of("id", "documentId", "chunkId"),
                "query", Map.of("knn", Map.of("embedding", fieldQuery)));
        JsonNode response = client.post("/" + properties.getIndex() + "/_search", body);
        JsonNode hitsNode = response.get("hits") == null ? null : response.get("hits").get("hits");
        if (hitsNode == null || !hitsNode.isArray()) return List.of();
        List<VectorSearchResult> results = new ArrayList<>();
        for (JsonNode hit : hitsNode) {
            JsonNode source = hit.get("_source");
            results.add(new VectorSearchResult(source.get("id").asString(), source.get("documentId").asString(),
                    source.get("chunkId").asString(), hit.get("_score").asDouble()));
        }
        return results;
    }

    private Map<String, Object> filter(Map<String, Object> filters) {
        List<Map<String, Object>> clauses = filters.entrySet().stream().map(entry -> {
            String field = "metadata." + entry.getKey();
            return Map.<String, Object>of("term", Map.of(field, entry.getValue()));
        }).toList();
        return clauses.size() == 1 ? clauses.getFirst() : Map.of("bool", Map.of("filter", clauses));
    }

    private String searchParameterName() {
        if (!properties.getEngine().equals("faiss")) return null;
        return properties.getIndexType().equals("ivf") ? "nprobes" : "ef_search";
    }

    @Override
    public void deleteAll() {
        client.post("/" + properties.getIndex() + "/_delete_by_query?refresh=true", Map.of("query", Map.of("match_all", Map.of())));
    }

    @Override
    public long count() {
        JsonNode response = client.get("/" + properties.getIndex() + "/_count");
        return response.get("count") == null ? 0 : response.get("count").asLong();
    }
}
