package com.myapp.infrastructure.vector.qdrant;

import com.myapp.infrastructure.vector.http.JsonHttpClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QdrantIndexManagerTest {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    void validatesPayloadIndexesEvenWhenCollectionUsesExactScan() {
        QdrantProperties properties = properties();
        QdrantIndexManager manager = new QdrantIndexManager(
                new StubClient("{\"result\":{\"payload_schema\":{}}}"), properties);

        assertThatThrownBy(() -> manager.awaitReady(20, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metadata.tenant_id");
    }

    @Test
    void acceptsDeclaredPayloadIndexOnExactScanPath() {
        QdrantProperties properties = properties();
        QdrantIndexManager manager = new QdrantIndexManager(new StubClient("""
                {"result":{"payload_schema":{"metadata.tenant_id":{"data_type":"keyword"}}}}
                """), properties);

        assertThatCode(() -> manager.awaitReady(20, Duration.ofSeconds(1))).doesNotThrowAnyException();
    }

    @Test
    void capturesServerFilledConfigAndValidatesDimensionAndDistance() {
        QdrantIndexManager manager = new QdrantIndexManager(new StubClient("""
                {"result":{"status":"green","indexed_vectors_count":20,
                "payload_schema":{"metadata.tenant_id":{"data_type":"keyword"}},
                "config":{"params":{"vectors":{"size":4,"distance":"Cosine"},"shard_number":1},
                "hnsw_config":{"m":16,"ef_construct":128,"full_scan_threshold":10},
                "optimizer_config":{"indexing_threshold":10},
                "quantization_config":null}}}
                """), properties());
        JsonNode actual = (JsonNode) manager.diagnostics().get("effectiveCollection");
        assertThat(actual.path("config").path("params").path("shard_number").asInt()).isOne();
        assertThat(actual.path("config").path("quantization_config").isNull()).isTrue();
        assertThat(actual.path("indexed_vectors_count").asInt()).isEqualTo(20);

        QdrantIndexManager mismatch = new QdrantIndexManager(new StubClient("""
                {"result":{"payload_schema":{"metadata.tenant_id":{"data_type":"keyword"}},
                "config":{"params":{"vectors":{"size":4,"distance":"Dot"}}}}}
                """), properties());
        assertThatThrownBy(mismatch::diagnostics).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension/distance");
    }

    private QdrantProperties properties() {
        QdrantProperties properties = new QdrantProperties();
        properties.setDimension(4);
        properties.setFullScanThreshold(10);
        properties.setPayloadIndexFields(Map.of("tenant_id", "keyword"));
        return properties;
    }

    private static final class StubClient extends JsonHttpClient {
        private final JsonNode response;

        private StubClient(String response) {
            super("http://localhost:6333", Map.of(), MAPPER);
            this.response = MAPPER.readTree(response);
        }

        @Override
        public JsonNode get(String path) {
            return response;
        }
    }
}
