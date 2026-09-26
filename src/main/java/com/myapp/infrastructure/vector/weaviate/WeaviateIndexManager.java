package com.myapp.infrastructure.vector.weaviate;

import com.myapp.infrastructure.vector.http.JsonHttpClient;
import com.myapp.infrastructure.vector.http.VectorStoreHttpException;
import com.myapp.port.VectorIndexManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;

public class WeaviateIndexManager implements VectorIndexManager {
    private final JsonHttpClient client;
    private final WeaviateProperties properties;

    public WeaviateIndexManager(JsonHttpClient client, WeaviateProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String indexType() { return properties.getIndexType(); }

    @Override
    public String engine() { return "Native"; }

    @Override
    public String searchParameterName() { return indexType().equals("hfresh") ? "searchProbe" : "ef"; }

    @Override
    public int minimumSearchParameter(int topK) { return indexType().equals("hfresh") ? 1 : topK; }

    @Override
    public void create() {
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
                property("externalId", "text"), property("documentId", "text"), property("chunkId", "text"),
                property("content", "text"), property("metadataJson", "text")
        ));
        properties.getFilterFields().forEach((name, type) -> {
            Map<String, Object> field = new LinkedHashMap<>(property(name, type));
            // Equal must compare the complete, case-sensitive metadata value, not word tokens.
            if (type.equalsIgnoreCase("text")) field.put("tokenization", "field");
            fields.add(field);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("class", properties.getClassName());
        body.put("vectorizer", "none");
        body.put("vectorIndexType", indexType());
        if (indexType().equals("hnsw")) {
            body.put("vectorIndexConfig", Map.of(
                    "distance", distance(), "maxConnections", properties.getHnswM(),
                    "efConstruction", properties.getEfConstruction(), "ef", properties.getEf()));
        } else {
            if (properties.getMetric() == com.myapp.domain.vector.DistanceMetric.DOT) {
                throw new IllegalStateException("Weaviate HFresh does not support dot distance");
            }
            body.put("vectorIndexConfig", Map.of(
                    "distance", distance(),
                    "maxPostingSizeKB", properties.getHfreshMaxPostingSizeKb(),
                    "replicas", properties.getHfreshReplicas(),
                    "searchProbe", properties.getHfreshSearchProbe()));
        }
        body.put("properties", fields);
        client.post("/v1/schema", body);
    }

    @Override
    public void drop() {
        try {
            client.delete("/v1/schema/" + properties.getClassName());
        } catch (VectorStoreHttpException exception) {
            if (exception.statusCode() != 404) throw exception;
        }
    }

    @Override
    public void configureSearch(Map<String, Object> searchParameters) {
        String parameterName = searchParameterName();
        Object requested = searchParameters.get(parameterName);
        if (requested == null) return;
        int value = requested instanceof Number number ? number.intValue() : Integer.parseInt(requested.toString());
        if (value < 1) throw new IllegalArgumentException(parameterName + " must be positive");
        JsonNode current = client.get("/v1/schema/" + properties.getClassName());
        JsonNode vectorIndexConfig = current.get("vectorIndexConfig");
        if (!(current instanceof ObjectNode) || !(vectorIndexConfig instanceof ObjectNode config)) {
            throw new IllegalStateException("Weaviate returned an invalid class schema");
        }
        config.put(parameterName, value);
        client.put("/v1/schema/" + properties.getClassName(), current);
        JsonNode applied = client.get("/v1/schema/" + properties.getClassName()).path("vectorIndexConfig").path(parameterName);
        if (!applied.isIntegralNumber() || applied.asInt() != value) {
            throw new IllegalStateException("Weaviate did not apply " + parameterName + "=" + value + "; actual=" + applied);
        }
    }

    @Override
    public void awaitReady(long expectedVectorCount, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode nodes = client.get("/v1/nodes?output=verbose&class=" + properties.getClassName()).get("nodes");
            long objectCount = 0;
            long queueLength = 0;
            boolean found = false;
            if (nodes != null && nodes.isArray()) {
                for (JsonNode node : nodes) {
                    JsonNode shards = node.get("shards");
                    if (shards == null || !shards.isArray()) continue;
                    for (JsonNode shard : shards) {
                        if (shard.get("class") == null || !properties.getClassName().equals(shard.get("class").asString())) continue;
                        found = true;
                        objectCount += shard.get("objectCount") == null ? 0 : shard.get("objectCount").asLong();
                        queueLength += shard.get("vectorQueueLength") == null ? 0 : shard.get("vectorQueueLength").asLong();
                    }
                }
            }
            if (found && objectCount >= expectedVectorCount && queueLength == 0) return;
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Weaviate indexing", exception);
            }
        }
        throw new IllegalStateException("Weaviate " + indexType() + " index did not become ready within " + timeout);
    }

    @Override
    public Map<String, Object> indexParameters() {
        if (indexType().equals("hnsw")) {
            return Map.of(
                    "maxConnections", properties.getHnswM(),
                    "efConstruction", properties.getEfConstruction(),
                    "distance", distance(),
                    "dimension", properties.getDimension());
        }
        return Map.of(
                "maxPostingSizeKB", properties.getHfreshMaxPostingSizeKb(),
                "replicas", properties.getHfreshReplicas(),
                "rqBits", 1,
                "distance", distance(),
                "dimension", properties.getDimension());
    }

    @Override
    public Map<String, Object> diagnostics() {
        JsonNode schema = client.get("/v1/schema/" + properties.getClassName());
        if (!indexType().equals(schema.path("vectorIndexType").asString())) {
            throw new IllegalStateException("Weaviate actual index type differs from requested " + indexType());
        }
        if (!distance().equals(schema.path("vectorIndexConfig").path("distance").asString())) {
            throw new IllegalStateException("Weaviate actual distance differs from requested " + distance());
        }
        JsonNode config = schema.path("vectorIndexConfig");
        if (indexType().equals("hnsw")) {
            requireSetting(config, "maxConnections", properties.getHnswM());
            requireSetting(config, "efConstruction", properties.getEfConstruction());
        } else {
            requireSetting(config, "maxPostingSizeKB", properties.getHfreshMaxPostingSizeKb());
            requireSetting(config, "replicas", properties.getHfreshReplicas());
        }
        for (Map.Entry<String, String> declared : properties.getFilterFields().entrySet()) {
            JsonNode actual = null;
            for (JsonNode field : schema.path("properties")) {
                if (declared.getKey().equals(field.path("name").asString())) actual = field;
            }
            if (actual == null || !actual.path("dataType").isArray() || actual.path("dataType").size() != 1
                    || !declared.getValue().equalsIgnoreCase(actual.path("dataType").get(0).asString())) {
                throw new IllegalStateException("Weaviate filter schema differs for " + declared.getKey());
            }
            if (declared.getValue().equalsIgnoreCase("text") && !"field".equals(actual.path("tokenization").asString())) {
                throw new IllegalStateException("Weaviate equality filter requires field tokenization: " + declared.getKey());
            }
        }
        // The server-filled schema records compression/rescore/sharding defaults, not just requested settings.
        return Map.of("effectiveSchema", schema,
                "nodes", client.get("/v1/nodes?output=verbose&class=" + properties.getClassName()));
    }

    private void requireSetting(JsonNode config, String key, int requested) {
        JsonNode actual = config.path(key);
        if (!actual.isIntegralNumber() || actual.asInt() != requested) {
            throw new IllegalStateException("Weaviate actual " + key + " differs from requested " + requested + "; actual=" + actual);
        }
    }

    private Map<String, Object> property(String name, String type) {
        return Map.of("name", name, "dataType", List.of(type));
    }

    private String distance() {
        return switch (properties.getMetric()) {
            case COSINE -> "cosine";
            case DOT -> "dot";
            case EUCLIDEAN -> "l2-squared";
        };
    }
}
