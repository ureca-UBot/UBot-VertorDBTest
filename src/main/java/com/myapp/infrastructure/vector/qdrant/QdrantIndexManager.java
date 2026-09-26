package com.myapp.infrastructure.vector.qdrant;

import com.myapp.infrastructure.vector.http.JsonHttpClient;
import com.myapp.infrastructure.vector.http.VectorStoreHttpException;
import com.myapp.port.VectorIndexManager;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import tools.jackson.databind.JsonNode;

public class QdrantIndexManager implements VectorIndexManager {
    private final JsonHttpClient client;
    private final QdrantProperties properties;

    public QdrantIndexManager(JsonHttpClient client, QdrantProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String indexType() { return "hnsw"; }

    @Override
    public String engine() { return "Native"; }

    @Override
    public String searchParameterName() { return "hnsw_ef"; }

    @Override
    public void create() {
        client.put("/collections/" + properties.getCollection(), Map.of(
                "vectors", Map.of("size", properties.getDimension(), "distance", distance()),
                "hnsw_config", Map.of("m", properties.getHnswM(), "ef_construct", properties.getEfConstruction(),
                        "full_scan_threshold", properties.getFullScanThreshold()),
                "optimizers_config", Map.of("indexing_threshold", properties.getIndexingThreshold())
        ));
        createPayloadIndexes();
    }

    /** Payload indexes permit cardinality-aware filtered search; Qdrant can still choose a scan for selective filters. */
    private void createPayloadIndexes() {
        properties.getPayloadIndexFields().forEach((key, schema) -> client.put(
                "/collections/" + properties.getCollection() + "/index?wait=true",
                Map.of("field_name", payloadField(key), "field_schema", schema)));
    }

    @Override
    public void drop() {
        try {
            client.delete("/collections/" + properties.getCollection());
        } catch (VectorStoreHttpException exception) {
            if (exception.statusCode() != 404) throw exception;
        }
    }

    @Override
    public void awaitReady(long expectedVectorCount, Duration timeout) {
        long estimatedVectorBytes = expectedVectorCount * (long) properties.getDimension() * Float.BYTES;
        long fullScanThresholdBytes = properties.getFullScanThreshold() * 1024L;
        if (estimatedVectorBytes < fullScanThresholdBytes) {
            // Qdrant deliberately uses exact scan below this collection-size threshold.
            JsonNode result = client.get("/collections/" + properties.getCollection()).get("result");
            requirePayloadIndexes(result);
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode result = client.get("/collections/" + properties.getCollection()).get("result");
            long indexed = result == null || result.get("indexed_vectors_count") == null ? 0 : result.get("indexed_vectors_count").asLong();
            String status = result == null || result.get("status") == null ? "" : result.get("status").asString();
            if (indexed >= expectedVectorCount && "green".equalsIgnoreCase(status)) {
                requirePayloadIndexes(result);
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Qdrant indexing", exception);
            }
        }
        throw new IllegalStateException("Qdrant HNSW index did not become ready within " + timeout);
    }

    /** A missing declared payload index changes the intended index configuration, so fail instead of measuring it. */
    private void requirePayloadIndexes(JsonNode collection) {
        if (collection == null || !collection.isObject()) throw new IllegalStateException("Qdrant returned no collection state");
        JsonNode schema = collection.get("payload_schema");
        List<String> missing = properties.getPayloadIndexFields().keySet().stream()
                .map(this::payloadField)
                .filter(field -> schema == null || schema.get(field) == null)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Qdrant payload index is missing for " + missing
                    + "; filtered search would use a different configuration");
        }
    }

    private String payloadField(String metadataKey) {
        return "metadata." + metadataKey;
    }

    @Override
    public Map<String, Object> indexParameters() {
        return Map.of(
                "m", properties.getHnswM(),
                "ef_construct", properties.getEfConstruction(),
                "full_scan_threshold_kb", properties.getFullScanThreshold(),
                "indexing_threshold_kb", properties.getIndexingThreshold(),
                "metric", properties.getMetric().name(),
                "dimension", properties.getDimension(),
                "payload_index_fields", properties.getPayloadIndexFields()
        );
    }

    @Override
    public Map<String, Object> diagnostics() {
        JsonNode collection = client.get("/collections/" + properties.getCollection()).get("result");
        requirePayloadIndexes(collection);
        JsonNode vectors = collection.path("config").path("params").path("vectors");
        if (vectors.path("size").asInt() != properties.getDimension()
                || !distance().equals(vectors.path("distance").asString())) {
            throw new IllegalStateException("Qdrant actual vector configuration differs from the requested dimension/distance");
        }
        JsonNode config = collection.path("config");
        requireSetting(config.path("hnsw_config"), "m", properties.getHnswM());
        requireSetting(config.path("hnsw_config"), "ef_construct", properties.getEfConstruction());
        requireSetting(config.path("hnsw_config"), "full_scan_threshold", properties.getFullScanThreshold());
        requireSetting(config.path("optimizer_config"), "indexing_threshold", properties.getIndexingThreshold());
        // Includes server-filled shard, replication, quantization and optimizer settings as well as current readiness.
        return Map.of("effectiveCollection", collection);
    }

    private void requireSetting(JsonNode config, String key, int requested) {
        JsonNode actual = config.path(key);
        if (!actual.isIntegralNumber() || actual.asInt() != requested) {
            throw new IllegalStateException("Qdrant actual " + key + " differs from requested " + requested + "; actual=" + actual);
        }
    }

    private String distance() {
        return switch (properties.getMetric()) {
            case COSINE -> "Cosine";
            case DOT -> "Dot";
            case EUCLIDEAN -> "Euclid";
        };
    }
}
