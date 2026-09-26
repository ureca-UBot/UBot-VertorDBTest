package com.myapp.infrastructure.vector.pgvector;

import com.myapp.domain.vector.VectorDocument;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgVectorIndexManagerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final List<VectorDocument> DOCUMENTS = List.of(
            new VectorDocument("one", "document", "one", "first", new float[]{1, 0}),
            new VectorDocument("two", "document", "two", "second", new float[]{0, 1}));

    @Test
    void trainsIvfflatOnlyAfterTheFullDatasetHasBeenUpserted() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        PgVectorProperties properties = properties("ivfflat");
        PgVectorIndexManager manager = new PgVectorIndexManager(jdbc, properties);
        PgVectorStore store = new PgVectorStore(jdbc, JsonMapper.builder().build(), properties);

        manager.rebuild(DOCUMENTS);
        assertThat(jdbc.statements).noneMatch(sql -> sql.startsWith("CREATE INDEX"));

        store.upsert(DOCUMENTS);
        manager.awaitReady(DOCUMENTS.size(), TIMEOUT);

        assertThat(jdbc.statements).anyMatch(sql -> sql.contains("USING ivfflat") && sql.contains("lists = 2"));
        assertThat(position(jdbc, "INSERT INTO"))
                .isLessThan(position(jdbc, "SELECT COUNT(*)"));
        assertThat(position(jdbc, "SELECT COUNT(*)"))
                .isLessThan(position(jdbc, "CREATE INDEX"));
    }

    @Test
    void keepsHnswCreationBeforeUpsertAndItsReadinessBarrierSynchronous() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        PgVectorProperties properties = properties("hnsw");
        PgVectorIndexManager manager = new PgVectorIndexManager(jdbc, properties);
        PgVectorStore store = new PgVectorStore(jdbc, JsonMapper.builder().build(), properties);

        manager.rebuild(DOCUMENTS);
        assertThat(jdbc.statements).anyMatch(sql -> sql.contains("USING hnsw"));
        store.upsert(DOCUMENTS);
        List<String> beforeReady = List.copyOf(jdbc.statements);
        manager.awaitReady(DOCUMENTS.size(), TIMEOUT);

        assertThat(position(jdbc, "CREATE INDEX")).isLessThan(position(jdbc, "INSERT INTO"));
        assertThat(jdbc.statements).containsExactlyElementsOf(beforeReady);
    }

    @Test
    void rejectsInvalidIvfListsBeforeDroppingTheExistingTable() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        PgVectorProperties properties = properties("ivfflat");
        properties.setIvfLists(0);
        PgVectorIndexManager manager = new PgVectorIndexManager(jdbc, properties);

        assertThatThrownBy(() -> manager.rebuild(DOCUMENTS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ivf-lists must be positive");
        assertThat(jdbc.statements).isEmpty();
    }

    @Test
    void refusesIvfTrainingWhenUpsertHasNotCompleted() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        PgVectorProperties properties = properties("ivfflat");
        PgVectorIndexManager manager = new PgVectorIndexManager(jdbc, properties);
        PgVectorStore store = new PgVectorStore(jdbc, JsonMapper.builder().build(), properties);

        manager.rebuild(DOCUMENTS);
        assertThatThrownBy(() -> manager.awaitReady(DOCUMENTS.size(), TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 2 vectors but table has 0");
        store.upsert(DOCUMENTS.subList(0, 1));
        assertThatThrownBy(() -> manager.awaitReady(DOCUMENTS.size(), TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 2 vectors but table has 1");
        assertThat(jdbc.statements).noneMatch(sql -> sql.startsWith("CREATE INDEX"));
    }

    private PgVectorProperties properties(String indexType) {
        PgVectorProperties properties = new PgVectorProperties();
        properties.setDimension(2);
        properties.setIndexType(indexType);
        properties.setIvfLists(2);
        return properties;
    }

    private int position(RecordingJdbcTemplate jdbc, String prefix) {
        for (int index = 0; index < jdbc.statements.size(); index++) {
            if (jdbc.statements.get(index).startsWith(prefix)) return index;
        }
        throw new AssertionError("Missing SQL statement: " + prefix);
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> statements = new ArrayList<>();
        private long rows;

        @Override
        public void execute(String sql) {
            statements.add(sql);
            if (sql.startsWith("DROP TABLE")) rows = 0;
        }

        @Override
        public int[] batchUpdate(String sql, BatchPreparedStatementSetter setter) {
            statements.add(sql);
            rows += setter.getBatchSize();
            return new int[setter.getBatchSize()];
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            statements.add(sql);
            return requiredType.cast(rows);
        }
    }
}
