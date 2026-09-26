package com.myapp.dataset;

import com.myapp.domain.vector.BenchmarkQuery;
import com.myapp.domain.vector.VectorFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public class QuerySetLoader {
    private final ObjectMapper objectMapper;

    public QuerySetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<BenchmarkQuery> load(Path definitionsPath, Path vectorsPath) {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        for (JsonNode node : JsonlSupport.read(definitionsPath, objectMapper)) {
            String id = JsonlSupport.text(node, "queryId", "query_id", "id");
            if (id == null) throw new IllegalArgumentException("Query definition is missing queryId");
            if (definitions.containsKey(id)) throw new IllegalArgumentException("Duplicate query definition id: " + id);
            Map<String, Object> filter = JsonlSupport.object(node, objectMapper, "filter");
            definitions.put(id, new Definition(
                    JsonlSupport.text(node, "query", "text"),
                    JsonlSupport.text(node, "queryType", "query_type"),
                    node.get("synthetic") != null && node.get("synthetic").asBoolean(),
                    filter));
        }

        Set<String> vectorIds = new HashSet<>();
        List<BenchmarkQuery> queries = JsonlSupport.readMapped(vectorsPath, objectMapper, node -> {
            String id = JsonlSupport.text(node, "queryId", "query_id", "id");
            if (id == null) throw new IllegalArgumentException("Query vector is missing queryId");
            if (!vectorIds.add(id)) throw new IllegalArgumentException("Duplicate query vector id: " + id);
            Definition definition = definitions.get(id);
            if (definition == null) throw new IllegalArgumentException("Query vector has no matching definition: " + id);
            Map<String, Object> inlineFilter = JsonlSupport.object(node, objectMapper, "filter");
            if (node.has("filter") && !inlineFilter.equals(definition.filter())) {
                throw new IllegalArgumentException("Inline filter conflicts with authoritative query definition: " + id);
            }
            boolean synthetic = definition.synthetic()
                    || (node.get("synthetic") != null && node.get("synthetic").asBoolean());
            return new BenchmarkQuery(id, definition.query(), definition.queryType(), synthetic,
                    JsonlSupport.vector(node, "embedding", "vector"), new VectorFilter(definition.filter()));
        });
        if (queries.isEmpty()) throw new IllegalStateException("Query vector dataset is empty: " + vectorsPath);
        if (!vectorIds.equals(definitions.keySet())) throw new IllegalArgumentException("Query definition IDs and vector IDs must match exactly");
        int dimension = queries.getFirst().embedding().length;
        if (queries.stream().anyMatch(query -> query.embedding().length != dimension)) {
            throw new IllegalArgumentException("All query vectors must use the same dimension");
        }
        return queries;
    }

    private record Definition(String query, String queryType, boolean synthetic, Map<String, Object> filter) {
    }
}
