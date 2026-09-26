package com.myapp.infrastructure.vector.weaviate;

import com.myapp.domain.vector.DistanceMetric;
import com.myapp.domain.vector.VectorDocument;
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
import java.util.Locale;
import java.util.Map;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.stream.Collectors;

public class WeaviateVectorStore implements VectorStore {
    private final JsonHttpClient client;
    private final WeaviateProperties properties;
    private final ObjectMapper objectMapper;

    public WeaviateVectorStore(JsonHttpClient client, WeaviateProperties properties, ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String database() { return "weaviate"; }

    @Override
    public int dimension() { return properties.getDimension(); }

    @Override
    public DistanceMetric metric() { return properties.getMetric(); }

    @Override
    public void upsert(List<VectorDocument> documents) {
        if (documents.isEmpty()) return;
        VectorHttpSupport.validateDimensions(documents, properties.getDimension());
        List<Map<String, Object>> objects = documents.stream().map(document -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("externalId", document.id());
            values.put("documentId", document.documentId());
            values.put("chunkId", document.chunkId());
            values.put("content", document.content());
            values.put("metadataJson", objectMapper.writeValueAsString(document.metadata()));
            properties.getFilterFields().keySet().forEach(key -> {
                if (document.metadata().containsKey(key)) values.put(key, document.metadata().get(key));
            });
            return Map.<String, Object>of(
                    "class", properties.getClassName(), "id", VectorHttpSupport.uuid(document.id()),
                    "properties", values, "vector", VectorHttpSupport.floats(document.embedding()));
        }).toList();
        JsonNode response = client.post("/v1/batch/objects", Map.of("objects", objects));
        if (response.isArray()) {
            for (JsonNode item : response) {
                JsonNode errors = item.get("result") == null ? null : item.get("result").get("errors");
                if (errors != null && !errors.isNull()) throw new IllegalStateException("Weaviate batch failed: " + errors);
            }
        }
    }

    @Override
    public List<VectorSearchResult> search(VectorSearchRequest request) {
        if (request.queryVector().length != properties.getDimension()) throw new IllegalArgumentException("Unexpected query vector dimension");
        String vector = VectorHttpSupport.floats(request.queryVector()).stream().map(String::valueOf).collect(Collectors.joining(","));
        String where = where(request);
        String query = "{Get{" + properties.getClassName() + "(nearVector:{vector:[" + vector + "]},limit:"
                + request.topK() + where + "){externalId documentId chunkId _additional{distance}}}}";
        JsonNode response = client.post("/v1/graphql", Map.of("query", query));
        JsonNode errors = response.get("errors");
        if (errors != null && !errors.isNull()) throw new IllegalStateException("Weaviate query failed: " + errors);
        JsonNode data = response.get("data");
        JsonNode rows = data == null || data.get("Get") == null ? null : data.get("Get").get(properties.getClassName());
        if (rows == null || !rows.isArray()) return List.of();
        List<VectorSearchResult> results = new ArrayList<>();
        for (JsonNode row : rows) {
            double distance = row.get("_additional").get("distance").asDouble();
            double score = properties.getMetric() == DistanceMetric.COSINE ? 1 - distance : -distance;
            results.add(new VectorSearchResult(row.get("externalId").asString(), row.get("documentId").asString(), row.get("chunkId").asString(), score));
        }
        return results;
    }

    private String where(VectorSearchRequest request) {
        if (request.filter().isEmpty()) return "";
        List<String> operands = request.filter().equals().entrySet().stream().map(entry -> {
            if (!properties.getFilterFields().containsKey(entry.getKey())) {
                throw new IllegalArgumentException("Weaviate filter field is not declared: " + entry.getKey());
            }
            String type = properties.getFilterFields().get(entry.getKey()).toLowerCase(Locale.ROOT);
            return "{path:[" + objectMapper.writeValueAsString(entry.getKey()) + "],operator:Equal,"
                    + filterLiteral(entry.getKey(), type, entry.getValue()) + "}";
        }).toList();
        String expression = operands.size() == 1 ? operands.getFirst() : "{operator:And,operands:[" + String.join(",", operands) + "]}";
        return ",where:" + expression;
    }

    private String filterLiteral(String field, String type, Object value) {
        // GraphQL filter value types follow the declared schema, not the Java number implementation.
        return switch (type) {
            case "int" -> "valueInt:" + integer(field, value);
            case "number" -> "valueNumber:" + number(field, value).toPlainString();
            case "boolean" -> {
                if (!(value instanceof Boolean)) throw invalidFilter(field, type);
                yield "valueBoolean:" + value;
            }
            case "text", "uuid", "date" -> {
                if (!(value instanceof String text)) throw invalidFilter(field, type);
                if (type.equals("date")) {
                    try {
                        OffsetDateTime.parse(text);
                    } catch (DateTimeParseException exception) {
                        throw new IllegalArgumentException("Weaviate date filter must be an RFC3339 timestamp: " + field, exception);
                    }
                }
                yield (type.equals("date") ? "valueDate:" : "valueText:") + objectMapper.writeValueAsString(text);
            }
            default -> throw new IllegalArgumentException("Unsupported Weaviate scalar equality filter type for " + field + ": " + type);
        };
    }

    private BigDecimal number(String field, Object value) {
        if (!(value instanceof Number)) throw invalidFilter(field, "number");
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Weaviate filter number must be finite: " + field, exception);
        }
    }

    private long integer(String field, Object value) {
        try {
            return number(field, value).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Weaviate int filter must be an exact 64-bit integer: " + field, exception);
        }
    }

    private IllegalArgumentException invalidFilter(String field, String type) {
        return new IllegalArgumentException("Weaviate filter value does not match declared " + type + " field: " + field);
    }

    @Override
    public void deleteAll() {
        client.sendRaw("DELETE", "/v1/batch/objects", objectMapper.writeValueAsString(Map.of(
                "match", Map.of("class", properties.getClassName(), "where", Map.of(
                        "path", List.of("externalId"), "operator", "Like", "valueText", "*")))), "application/json");
    }

    @Override
    public long count() {
        String query = "{Aggregate{" + properties.getClassName() + "{meta{count}}}}";
        JsonNode response = client.post("/v1/graphql", Map.of("query", query));
        JsonNode data = response.get("data");
        JsonNode rows = data == null || data.get("Aggregate") == null ? null : data.get("Aggregate").get(properties.getClassName());
        return rows == null || !rows.isArray() || rows.isEmpty() ? 0 : rows.get(0).get("meta").get("count").asLong();
    }
}
