package com.myapp.infrastructure.vector.qdrant;

import com.myapp.domain.vector.DistanceMetric;
import com.myapp.domain.vector.VectorFilter;
import com.myapp.domain.vector.VectorSearchRequest;
import com.myapp.domain.vector.VectorSearchResult;
import com.myapp.infrastructure.vector.http.JsonHttpClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantVectorStoreTest {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    void requestsOnlyCommonResultFieldsAndPreservesTypedEquality() {
        CapturingClient client = new CapturingClient();
        QdrantVectorStore store = new QdrantVectorStore(client, properties(DistanceMetric.COSINE));

        List<VectorSearchResult> results = store.search(new VectorSearchRequest(new float[]{1, 0}, 10,
                new VectorFilter(Map.of("tenant", "Alpha-test", "generation", 2, "active", true)), Map.of("hnsw_ef", 40)));

        assertThat(client.path).isEqualTo("/collections/benchmark_chunks/points/query");
        assertThat(client.body.path("with_payload")).isEqualTo(MAPPER.valueToTree(List.of("id", "documentId", "chunkId")));
        assertThat(client.body.path("with_vector").asBoolean()).isFalse();
        assertThat(client.body.path("params").path("hnsw_ef").asInt()).isEqualTo(40);
        assertThat(client.body.path("filter").path("must")).containsExactlyInAnyOrder(
                MAPPER.valueToTree(Map.of("key", "metadata.tenant", "match", Map.of("value", "Alpha-test"))),
                MAPPER.valueToTree(Map.of("key", "metadata.generation", "match", Map.of("value", 2))),
                MAPPER.valueToTree(Map.of("key", "metadata.active", "match", Map.of("value", true))));
        assertThat(results).containsExactly(new VectorSearchResult("a", "doc-a", "chunk-a", 0.25));
    }

    @Test
    void exposesEuclideanDistanceAsHigherIsBetterScore() {
        CapturingClient client = new CapturingClient();
        QdrantVectorStore store = new QdrantVectorStore(client, properties(DistanceMetric.EUCLIDEAN));

        assertThat(store.search(new VectorSearchRequest(new float[]{1, 0}, 1, VectorFilter.NONE)).getFirst().score())
                .isEqualTo(-0.25);
    }

    private QdrantProperties properties(DistanceMetric metric) {
        QdrantProperties properties = new QdrantProperties();
        properties.setDimension(2);
        properties.setMetric(metric);
        return properties;
    }

    private static final class CapturingClient extends JsonHttpClient {
        private String path;
        private JsonNode body;

        private CapturingClient() {
            super("http://localhost:6333", Map.of(), MAPPER);
        }

        @Override
        public JsonNode post(String path, Object body) {
            this.path = path;
            this.body = MAPPER.readTree(MAPPER.writeValueAsString(body));
            return MAPPER.readTree("""
                    {"result":{"points":[{"payload":{"id":"a","documentId":"doc-a","chunkId":"chunk-a"},"score":0.25}]}}
                    """);
        }
    }
}
