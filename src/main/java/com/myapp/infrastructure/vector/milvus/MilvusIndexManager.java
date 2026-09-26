package com.myapp.infrastructure.vector.milvus;

import com.myapp.infrastructure.vector.http.JsonHttpClient;
import com.myapp.port.VectorIndexManager;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.utility.request.GetQuerySegmentInfoReq;
import io.milvus.v2.service.utility.response.GetQuerySegmentInfoResp;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public class MilvusIndexManager implements VectorIndexManager {
    private final JsonHttpClient client;
    private final MilvusProperties properties;
    private final MilvusClientV2 diagnosticClient;

    public MilvusIndexManager(JsonHttpClient client, MilvusProperties properties) {
        this(client, properties, null);
    }

    public MilvusIndexManager(JsonHttpClient client, MilvusProperties properties, MilvusClientV2 diagnosticClient) {
        this.client = client;
        this.properties = properties;
        this.diagnosticClient = diagnosticClient;
    }

    @Override
    public String indexType() { return properties.getIndexType(); }

    @Override
    public String engine() { return "Native"; }

    @Override
    public boolean requiresStabilityCheck() { return true; }

    @Override
    public String searchParameterName() {
        if (indexType().equals("HNSW")) return "ef";
        if (indexType().equals("DISKANN")) return "search_list";
        return "nprobe";
    }

    @Override
    public int minimumSearchParameter(int topK) {
        return indexType().equals("HNSW") || indexType().equals("DISKANN") ? topK : 1;
    }

    @Override
    public int maximumSearchParameter() {
        return indexType().startsWith("IVF_") ? properties.getIvfNlist() : Integer.MAX_VALUE;
    }

    @Override
    public void create() {
        List<Map<String, Object>> fields = List.of(
                field("id", "VarChar", true, Map.of("max_length", "256")),
                field("document_id", "VarChar", false, Map.of("max_length", "256")),
                field("chunk_id", "VarChar", false, Map.of("max_length", "256")),
                field("content", "VarChar", false, Map.of("max_length", "65535")),
                field("metadata", "JSON", false, Map.of()),
                field("embedding", "FloatVector", false, Map.of("dim", Integer.toString(properties.getDimension())))
        );
        Map<String, Object> body = Map.of(
                "dbName", properties.getDatabase(),
                "collectionName", properties.getCollection(),
                "schema", Map.of("autoId", false, "enabledDynamicField", false, "fields", fields),
                "indexParams", List.of(Map.of(
                        "metricType", metric(), "fieldName", "embedding", "indexName", indexName(),
                        "indexType", indexType(), "params", buildParameters()))
        );
        requireSuccess(client.post("/v2/vectordb/collections/create", body));
    }

    @Override
    public void drop() {
        JsonNode response = client.post("/v2/vectordb/collections/list", Map.of("dbName", properties.getDatabase()));
        requireSuccess(response);
        JsonNode data = response.get("data");
        boolean exists = false;
        if (data != null && data.isArray()) {
            for (JsonNode collection : data) {
                if (properties.getCollection().equals(collection.asString())) {
                    exists = true;
                    break;
                }
            }
        }
        if (exists) requireSuccess(client.post("/v2/vectordb/collections/drop", identity()));
    }

    @Override
    public void awaitReady(long expectedVectorCount, Duration timeout) {
        // Inserts first land in growing segments. Flush seals them so Milvus builds the
        // configured ANN index instead of serving the benchmark from an exact growing segment.
        requireSuccess(client.post("/v2/vectordb/collections/flush", identity()));
        // Loading is asynchronous. An index can already report Finished while the query node
        // still serves a transitional path, which previously produced a large tuning/measurement
        // recall drift. Explicitly request load and require 100% before calibration starts.
        requireSuccess(client.post("/v2/vectordb/collections/load", identity()));

        long deadline = System.nanoTime() + timeout.toNanos();
        Map<String, Object> request = Map.of(
                "dbName", properties.getDatabase(),
                "collectionName", properties.getCollection(),
                "indexName", indexName()
        );
        while (System.nanoTime() < deadline) {
            JsonNode response = client.post("/v2/vectordb/indexes/describe", request);
            requireSuccess(response);
            JsonNode indexes = response.get("data");
            if (indexes != null && indexes.isArray() && !indexes.isEmpty()) {
                JsonNode index = indexes.get(0);
                String state = text(index, "indexState");
                long indexedRows = number(index, "indexedRows");
                long pendingRows = number(index, "pendingRows");
                JsonNode loadResponse = client.post("/v2/vectordb/collections/get_load_state", identity());
                requireSuccess(loadResponse);
                JsonNode load = loadResponse.get("data");
                String loadState = load == null ? "" : text(load, "loadState");
                long loadProgress = load == null ? 0 : number(load, "loadProgress");
                if ("Finished".equalsIgnoreCase(state)
                        && indexedRows >= expectedVectorCount
                        && pendingRows == 0
                        && "LoadStateLoaded".equalsIgnoreCase(loadState)
                        && loadProgress == 100
                        && querySegmentsReady(expectedVectorCount)) {
                    return;
                }
                String failure = text(index, "failReason");
                if (!failure.isBlank()) throw new IllegalStateException("Milvus index build failed: " + failure);
            }
            sleepBeforeRetry();
        }
        throw new IllegalStateException("Milvus " + indexType()
                + " index and collection load did not become ready within " + timeout);
    }

    @Override
    public Map<String, Object> indexParameters() {
        Map<String, Object> parameters = new LinkedHashMap<>(buildParameters());
        parameters.put("metricType", metric());
        parameters.put("dimension", properties.getDimension());
        return Map.copyOf(parameters);
    }

    @Override
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> indexRequest = Map.of(
                "dbName", properties.getDatabase(),
                "collectionName", properties.getCollection(),
                "indexName", indexName());
        try {
            JsonNode indexState = client.post("/v2/vectordb/indexes/describe", indexRequest);
            requireSuccess(indexState);
            result.put("indexState", indexState.get("data"));
            JsonNode loadState = client.post("/v2/vectordb/collections/get_load_state", identity());
            requireSuccess(loadState);
            result.put("loadState", loadState.get("data"));
        } catch (RuntimeException exception) {
            result.put("restDiagnosticError", exception.getMessage());
        }
        if (diagnosticClient == null) {
            result.put("segmentDiagnosticError", "Milvus SDK diagnostic client is unavailable");
            return Map.copyOf(result);
        }
        try {
            GetQuerySegmentInfoResp response = diagnosticClient.getQuerySegmentInfo(GetQuerySegmentInfoReq.builder()
                    .databaseName(properties.getDatabase())
                    .collectionName(properties.getCollection())
                    .build());
            result.put("querySegments", response.getSegmentInfos());
        } catch (RuntimeException exception) {
            result.put("segmentDiagnosticError", exception.getMessage());
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> buildParameters() {
        return switch (indexType()) {
            case "HNSW" -> Map.of("M", properties.getHnswM(), "efConstruction", properties.getEfConstruction());
            case "IVF_FLAT", "IVF_SQ8" -> Map.of("nlist", properties.getIvfNlist());
            case "IVF_PQ" -> {
                if (properties.getDimension() % properties.getPqM() != 0) {
                    throw new IllegalStateException("Milvus IVF_PQ requires dimension divisible by pq-m");
                }
                yield Map.of("nlist", properties.getIvfNlist(), "m", properties.getPqM(), "nbits", properties.getPqNbits());
            }
            case "DISKANN" -> Map.of();
            default -> throw new IllegalStateException("Unsupported Milvus index type: " + indexType());
        };
    }

    private String indexName() {
        return "embedding_ann";
    }

    private Map<String, Object> identity() {
        return Map.of("dbName", properties.getDatabase(), "collectionName", properties.getCollection());
    }

    private Map<String, Object> field(String name, String type, boolean primary, Map<String, Object> params) {
        return Map.of("fieldName", name, "dataType", type, "isPrimary", primary, "elementTypeParams", params);
    }

    private String metric() {
        return switch (properties.getMetric()) {
            case COSINE -> "COSINE";
            case DOT -> "IP";
            case EUCLIDEAN -> "L2";
        };
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? "" : value.asString();
    }

    private long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? 0 : value.asLong();
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Milvus indexing", exception);
        }
    }

    private boolean querySegmentsReady(long expectedVectorCount) {
        if (diagnosticClient == null) return false;
        try {
            List<GetQuerySegmentInfoResp.QuerySegmentInfo> segments = diagnosticClient
                    .getQuerySegmentInfo(GetQuerySegmentInfoReq.builder()
                            .databaseName(properties.getDatabase())
                            .collectionName(properties.getCollection())
                            .build())
                    .getSegmentInfos();
            long rows = segments.stream().mapToLong(segment -> segment.getNumOfRows() == null
                    ? 0 : segment.getNumOfRows()).sum();
            return !segments.isEmpty()
                    && rows >= expectedVectorCount
                    && segments.stream().allMatch(segment -> segment.getIndexName() != null
                            && !segment.getIndexName().isBlank()
                            && ("Sealed".equalsIgnoreCase(segment.getState())
                            || "Flushed".equalsIgnoreCase(segment.getState())));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static void requireSuccess(JsonNode response) {
        JsonNode code = response.get("code");
        if (code != null && code.asInt() != 0) throw new IllegalStateException("Milvus request failed: " + response);
    }
}
