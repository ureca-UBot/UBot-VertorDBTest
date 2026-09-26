package com.myapp.infrastructure.vector.weaviate;

import com.myapp.domain.vector.DistanceMetric;
import com.myapp.domain.vector.VectorFilter;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import com.myapp.infrastructure.vector.http.JsonHttpClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeaviateAdapterContractTest {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    void createsWholeFieldTokenizationForTextEqualityMetadataOnly() {
        CapturingClient client = new CapturingClient();
        WeaviateProperties properties = properties();
        new WeaviateIndexManager(client, properties).create();

        Map<String, JsonNode> fields = new java.util.HashMap<>();
        client.body.path("properties").forEach(field -> fields.put(field.path("name").asString(), field));
        assertThat(fields.get("tenant").path("tokenization").asString()).isEqualTo("field");
        assertThat(fields.get("generation").has("tokenization")).isFalse();
        assertThat(fields.get("content").has("tokenization")).isFalse();
    }

    @Test
    void serializesEqualityUsingDeclaredScalarTypesAndOnlyCommonResultFields() {
        CapturingClient client = new CapturingClient();
        WeaviateVectorStore store = new WeaviateVectorStore(client, properties(), MAPPER);
        var results = store.search(request(Map.of("tenant", "Alpha\n\"test", "generation", 2.0,
                "rating", 2, "active", true, "created", "2026-09-12T00:00:00Z")));

        String query = client.body.path("query").asString();
        assertThat(query).contains("path:[\"generation\"],operator:Equal,valueInt:2}",
                "path:[\"rating\"],operator:Equal,valueNumber:2}",
                "path:[\"active\"],operator:Equal,valueBoolean:true}",
                "path:[\"created\"],operator:Equal,valueDate:\"2026-09-12T00:00:00Z\"}",
                "path:[\"tenant\"],operator:Equal,valueText:\"Alpha\\n\\\"test\"}",
                "{externalId documentId chunkId _additional{distance}}");
        assertThat(query).doesNotContain("metadataJson", "content", "valueInt:2.0");
        assertThat(results).containsExactly(new VectorSearchResult("a", "doc-a", "chunk-a", 0.75));
    }

    @Test
    void rejectsFractionalIntegerAndStringCoercionBeforeSendingRequest() {
        CapturingClient client = new CapturingClient();
        WeaviateVectorStore store = new WeaviateVectorStore(client, properties(), MAPPER);
        assertThatThrownBy(() -> store.search(request(Map.of("generation", 2.5))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact 64-bit integer");
        assertThatThrownBy(() -> store.search(request(Map.of("generation", "2"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("declared number");
        assertThatThrownBy(() -> store.search(request(Map.of("rating", Double.NaN))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finite");
        assertThatThrownBy(() -> store.search(request(Map.of("active", "true"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("declared boolean");
        assertThatThrownBy(() -> store.search(request(Map.of("created", "2026-09-12"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RFC3339");
        assertThat(client.body).isNull();
    }

    @Test
    void requiresServerReadbackOfConfiguredSearchParameter() {
        CapturingClient client = new CapturingClient();
        client.acceptConfiguration = false;
        WeaviateIndexManager manager = new WeaviateIndexManager(client, properties());
        assertThatThrownBy(() -> manager.configureSearch(Map.of("ef", 50)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not apply ef=50");

        client.acceptConfiguration = true;
        manager.configureSearch(Map.of("ef", 50));
        assertThat(client.schema.path("vectorIndexConfig").path("ef").asInt()).isEqualTo(50);
    }

    @Test
    void capturesEffectiveCompressionAndShardSettingsInsteadOfAssumingDefaults() {
        CapturingClient client = new CapturingClient();
        WeaviateIndexManager manager = new WeaviateIndexManager(client, properties());
        JsonNode schema = (JsonNode) manager.diagnostics().get("effectiveSchema");
        assertThat(schema.path("vectorIndexConfig").path("rq").path("enabled").asBoolean()).isTrue();
        assertThat(schema.path("shardingConfig").path("desiredCount").asInt()).isOne();
    }

    @Test
    void rejectsLegacyWordTokenizationOnExistingIndex() {
        CapturingClient client = new CapturingClient();
        client.schema = MAPPER.readTree(MAPPER.writeValueAsString(client.schema).replace("\"tokenization\":\"field\"", "\"tokenization\":\"word\""));
        assertThatThrownBy(() -> new WeaviateIndexManager(client, properties()).diagnostics())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("requires field tokenization: tenant");
    }

    @Test
    void normalizesNonCosineDistancesToHigherIsBetterScores() {
        CapturingClient client = new CapturingClient();
        WeaviateProperties properties = properties();
        properties.setMetric(DistanceMetric.EUCLIDEAN);
        assertThat(new WeaviateVectorStore(client, properties, MAPPER).search(request(Map.of())).getFirst().score())
                .isEqualTo(-0.25);
    }

    private static WeaviateProperties properties() {
        WeaviateProperties properties = new WeaviateProperties();
        properties.setDimension(2);
        properties.setFilterFields(Map.of("tenant", "text", "generation", "int", "rating", "number", "active", "boolean", "created", "date"));
        return properties;
    }

    private VectorSearchRequest request(Map<String, Object> filter) {
        return new VectorSearchRequest(new float[]{1, 0}, 10, new VectorFilter(filter), Map.of("ef", 40));
    }

    private static final class CapturingClient extends JsonHttpClient {
        private JsonNode body;
        private boolean acceptConfiguration = true;
        private JsonNode schema = MAPPER.readTree("""
                {"class":"BenchmarkChunk","vectorIndexType":"hnsw", "vectorIndexConfig":{"distance":"cosine","maxConnections":16,"efConstruction":128,"ef":100,"rq":{"enabled":true,"bits":8,"rescoreLimit":20}},
                "shardingConfig":{"desiredCount":1}, "properties":[
                {"name":"tenant","dataType":["text"],"tokenization":"field"},
                {"name":"generation","dataType":["int"]},{"name":"rating","dataType":["number"]},
                {"name":"active","dataType":["boolean"]},{"name":"created","dataType":["date"]}]}
                """);

        private CapturingClient() {
            super("http://localhost:18080", Map.of(), MAPPER);
        }

        @Override
        public JsonNode get(String path) {
            return path.startsWith("/v1/schema/") ? schema.deepCopy() : MAPPER.readTree("{\"nodes\":[]}");
        }

        @Override
        public JsonNode post(String path, Object body) {
            this.body = MAPPER.readTree(MAPPER.writeValueAsString(body));
            return MAPPER.readTree("""
                    {"data":{"Get":{"BenchmarkChunk":[{"externalId":"a","documentId":"doc-a","chunkId":"chunk-a","_additional":{"distance":0.25}}]}}}
                    """);
        }

        @Override
        public JsonNode put(String path, Object body) {
            this.body = MAPPER.readTree(MAPPER.writeValueAsString(body));
            if (acceptConfiguration) schema = this.body;
            return MAPPER.createObjectNode();
        }
    }
}
