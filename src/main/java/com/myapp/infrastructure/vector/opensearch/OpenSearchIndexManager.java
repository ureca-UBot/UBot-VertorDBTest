package com.myapp.infrastructure.vector.opensearch;

import com.myapp.domain.vector.VectorDocument;
import com.myapp.infrastructure.vector.http.JsonHttpClient;
import com.myapp.infrastructure.vector.http.VectorHttpSupport;
import com.myapp.infrastructure.vector.http.VectorStoreHttpException;
import com.myapp.port.VectorIndexManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenSearchIndexManager implements VectorIndexManager {
    private final JsonHttpClient client;
    private final OpenSearchProperties properties;
    private final ObjectMapper objectMapper;

    public OpenSearchIndexManager(JsonHttpClient client, OpenSearchProperties properties, ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        validateCombination();
    }

    @Override
    public String indexType() {
        return switch (properties.getIndexType()) {
            case "hnsw" -> "HNSW";
            case "ivf" -> "IVF";
            case "disk_ann" -> "DiskANN";
            default -> throw new IllegalStateException("Unsupported OpenSearch index type: " + properties.getIndexType());
        };
    }

    @Override
    public String engine() {
        String value = properties.getEngine();
        if (value.equals("jvector")) return "JVector";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    @Override
    public String searchParameterName() {
        if (!properties.getEngine().equals("faiss")) return "candidate_k";
        return properties.getIndexType().equals("ivf") ? "nprobes" : "ef_search";
    }

    @Override
    public int minimumSearchParameter(int topK) {
        return properties.getIndexType().equals("ivf") ? 1 : topK;
    }

    @Override
    public int maximumSearchParameter() {
        if (properties.getIndexType().equals("ivf")) return properties.getIvfNlist();
        return properties.getEngine().equals("faiss") ? Integer.MAX_VALUE : 10_000;
    }

    @Override
    public void create() {
        createTargetIndex();
    }

    @Override
    public void rebuild(List<VectorDocument> trainingDocuments) {
        drop();
        if (!properties.getIndexType().equals("ivf")) {
            createTargetIndex();
            return;
        }
        trainIvfModel(trainingDocuments);
        createTargetIndex();
        deleteIndexIfPresent(properties.getTrainingIndex());
    }

    @Override
    public void drop() {
        deleteIndexIfPresent(properties.getIndex());
        if (properties.getIndexType().equals("ivf")) {
            deleteIndexIfPresent(properties.getTrainingIndex());
            try {
                client.delete("/_plugins/_knn/models/" + properties.getIvfModelId());
            } catch (VectorStoreHttpException exception) {
                if (exception.statusCode() != 404) throw exception;
            }
        }
    }

    @Override
    public void awaitReady(long expectedVectorCount, Duration timeout) {
        client.post("/" + properties.getIndex() + "/_refresh", Map.of());
        client.post("/" + properties.getIndex() + "/_forcemerge?max_num_segments=1&flush=true", Map.of());
    }

    @Override
    public long indexSizeBytes() {
        JsonNode response = client.get("/" + properties.getIndex() + "/_stats/store");
        JsonNode indices = response.get("indices");
        JsonNode index = indices == null ? null : indices.get(properties.getIndex());
        JsonNode size = index == null || index.get("total") == null || index.get("total").get("store") == null
                ? null : index.get("total").get("store").get("size_in_bytes");
        return size == null ? -1 : size.asLong();
    }

    @Override
    public Map<String, Object> indexParameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("engine", properties.getEngine());
        parameters.put("method", properties.getIndexType());
        if (properties.getIndexType().equals("hnsw") || properties.getIndexType().equals("disk_ann")) {
            parameters.put("m", properties.getHnswM());
            parameters.put("ef_construction", properties.getEfConstruction());
        }
        if (properties.getIndexType().equals("ivf")) parameters.put("nlist", properties.getIvfNlist());
        parameters.put("space_type", spaceType());
        parameters.put("dimension", properties.getDimension());
        return Map.copyOf(parameters);
    }

    @Override
    public Map<String, Object> diagnostics() {
        JsonNode mappings = client.get("/" + properties.getIndex() + "/_mapping");
        JsonNode settings = client.get("/" + properties.getIndex() + "/_settings?include_defaults=true");
        JsonNode embedding = mappings.path(properties.getIndex()).path("mappings").path("properties").path("embedding");
        JsonNode indexSettings = settings.path(properties.getIndex()).path("settings").path("index");
        if (!"knn_vector".equals(embedding.path("type").asString())
                || indexSettings.path("number_of_shards").asInt() != 1
                || indexSettings.path("number_of_replicas").asInt() != 0) {
            throw new IllegalStateException("OpenSearch actual vector mapping/shard/replica settings differ from requested configuration");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("effectiveMappings", mappings);
        result.put("effectiveSettingsIncludingDefaults", settings);
        result.put("segments", client.get("/" + properties.getIndex() + "/_segments"));
        if (properties.getIndexType().equals("ivf")) {
            if (!properties.getIvfModelId().equals(embedding.path("model_id").asString())) {
                throw new IllegalStateException("OpenSearch actual IVF model differs from requested model");
            }
            JsonNode model = client.get("/_plugins/_knn/models/" + properties.getIvfModelId());
            if (!"created".equalsIgnoreCase(model.path("state").asString())) {
                throw new IllegalStateException("OpenSearch IVF model is not ready: " + model);
            }
            result.put("effectiveModel", model);
        } else {
            JsonNode method = embedding.path("method");
            if (embedding.path("dimension").asInt() != properties.getDimension()
                    || !properties.getEngine().equals(method.path("engine").asString())
                    || !properties.getIndexType().equals(method.path("name").asString())
                    || !spaceType().equals(method.path("space_type").asString())
                    || method.path("parameters").path("m").asInt() != properties.getHnswM()
                    || method.path("parameters").path("ef_construction").asInt() != properties.getEfConstruction()) {
                throw new IllegalStateException("OpenSearch actual vector method differs from requested configuration");
            }
        }
        return Map.copyOf(result);
    }

    private void createTargetIndex() {
        Map<String, Object> vector;
        if (properties.getIndexType().equals("ivf")) {
            vector = Map.of("type", "knn_vector", "model_id", properties.getIvfModelId());
        } else {
            vector = Map.of(
                    "type", "knn_vector",
                    "dimension", properties.getDimension(),
                    "method", Map.of(
                            "name", properties.getIndexType(),
                            "engine", properties.getEngine(),
                            "space_type", spaceType(),
                            "parameters", Map.of(
                                    "m", properties.getHnswM(),
                                    "ef_construction", properties.getEfConstruction())));
        }
        client.put("/" + properties.getIndex(), Map.of(
                "settings", Map.of("index", Map.of("knn", true, "number_of_shards", 1, "number_of_replicas", 0)),
                "mappings", Map.of("date_detection", false,
                        "dynamic_templates", List.of(Map.of("metadata_strings", Map.of(
                                "path_match", "metadata.*", "match_mapping_type", "string", "mapping", Map.of("type", "keyword")))),
                        "properties", Map.of(
                        "id", Map.of("type", "keyword"),
                        "documentId", Map.of("type", "keyword"),
                        "chunkId", Map.of("type", "keyword"),
                        "content", Map.of("type", "text", "index", false),
                        "metadata", Map.of("type", "object", "dynamic", true),
                        "embedding", vector))));
    }

    private void trainIvfModel(List<VectorDocument> documents) {
        if (documents.size() < properties.getIvfNlist()) {
            throw new IllegalStateException("OpenSearch IVF training requires at least nlist vectors");
        }
        client.put("/" + properties.getTrainingIndex(), Map.of(
                "settings", Map.of("number_of_shards", 1, "number_of_replicas", 0),
                "mappings", Map.of("properties", Map.of(
                        "training_vector", Map.of("type", "knn_vector", "dimension", properties.getDimension())))));
        for (int start = 0; start < documents.size(); start += 256) {
            StringBuilder bulk = new StringBuilder();
            for (VectorDocument document : documents.subList(start, Math.min(start + 256, documents.size()))) {
                bulk.append(objectMapper.writeValueAsString(Map.of(
                        "index", Map.of("_index", properties.getTrainingIndex(), "_id", document.id())))).append('\n');
                bulk.append(objectMapper.writeValueAsString(Map.of(
                        "training_vector", VectorHttpSupport.floats(document.embedding())))).append('\n');
            }
            JsonNode response = client.sendRaw("POST", "/_bulk", bulk.toString(), "application/x-ndjson");
            if (response.get("errors") != null && response.get("errors").asBoolean()) {
                throw new IllegalStateException("OpenSearch IVF training data bulk request failed: " + response);
            }
        }
        client.post("/" + properties.getTrainingIndex() + "/_refresh", Map.of());
        client.post("/_plugins/_knn/models/" + properties.getIvfModelId() + "/_train", Map.of(
                "training_index", properties.getTrainingIndex(),
                "training_field", "training_vector",
                "dimension", properties.getDimension(),
                "description", "VectorDbTest Faiss IVF model",
                "method", Map.of(
                        "name", "ivf", "engine", "faiss", "space_type", spaceType(),
                        "parameters", Map.of("nlist", properties.getIvfNlist(), "nprobes", 1))));
        waitForModel(Duration.ofMinutes(10));
    }

    private void waitForModel(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode model = client.get("/_plugins/_knn/models/" + properties.getIvfModelId());
            String state = model.get("state") == null ? "" : model.get("state").asString();
            if ("created".equalsIgnoreCase(state)) return;
            if ("failed".equalsIgnoreCase(state)) throw new IllegalStateException("OpenSearch IVF model training failed: " + model);
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for OpenSearch IVF training", exception);
            }
        }
        throw new IllegalStateException("OpenSearch IVF model did not finish training within " + timeout);
    }

    private void deleteIndexIfPresent(String index) {
        try {
            client.delete("/" + index);
        } catch (VectorStoreHttpException exception) {
            if (exception.statusCode() != 404) throw exception;
        }
    }

    private void validateCombination() {
        String combination = properties.getEngine() + "/" + properties.getIndexType();
        if (!java.util.Set.of("lucene/hnsw", "faiss/hnsw", "faiss/ivf", "jvector/disk_ann").contains(combination)) {
            throw new IllegalStateException("Unsupported OpenSearch engine/index combination: " + combination);
        }
    }

    private String spaceType() {
        return switch (properties.getMetric()) {
            case COSINE -> "cosinesimil";
            case DOT -> "innerproduct";
            case EUCLIDEAN -> "l2";
        };
    }
}
