package com.myapp.infrastructure.rdb.postgres;

import com.myapp.domain.vector.VectorDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Keeps PostgreSQL's source snapshot aligned with the immutable vector benchmark input. */
@Component
@ConditionalOnProperty(prefix = "spring.data.jdbc.repositories", name = "enabled", havingValue = "true")
public class BenchmarkSourceOfTruthSynchronizer {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BenchmarkSourceOfTruthSynchronizer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Snapshot synchronize(List<VectorDocument> chunks, String datasetSha256, boolean rebuildAndLoad) {
        if (chunks == null || chunks.isEmpty()) throw new IllegalArgumentException("chunks must not be empty");
        if (datasetSha256 == null || datasetSha256.isBlank()) {
            throw new IllegalArgumentException("datasetSha256 must not be blank");
        }

        int documentCount = Math.toIntExact(chunks.stream().map(VectorDocument::documentId).distinct().count());
        if (matchesCurrentSnapshot(datasetSha256, documentCount, chunks.size())) {
            return new Snapshot(datasetSha256, documentCount, chunks.size(), false);
        }
        if (!rebuildAndLoad) {
            throw new IllegalStateException(
                    "PostgreSQL source snapshot does not match the benchmark input; run with rebuildAndLoad=true");
        }

        Map<String, List<VectorDocument>> byDocument = chunks.stream().collect(Collectors.groupingBy(
                VectorDocument::documentId, LinkedHashMap::new, Collectors.toList()));
        jdbcTemplate.update("DELETE FROM document_chunks");
        jdbcTemplate.update("DELETE FROM documents");
        insertDocuments(byDocument);
        insertChunks(byDocument);
        jdbcTemplate.update("""
                INSERT INTO benchmark_dataset_state
                    (singleton_id, document_vectors_sha256, document_count, chunk_count, updated_at)
                VALUES (1, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (singleton_id) DO UPDATE SET
                    document_vectors_sha256 = EXCLUDED.document_vectors_sha256,
                    document_count = EXCLUDED.document_count,
                    chunk_count = EXCLUDED.chunk_count,
                    updated_at = EXCLUDED.updated_at
                """, datasetSha256, documentCount, chunks.size());
        return new Snapshot(datasetSha256, documentCount, chunks.size(), true);
    }

    private boolean matchesCurrentSnapshot(String datasetSha256, int documentCount, int chunkCount) {
        List<State> states = jdbcTemplate.query("""
                        SELECT document_vectors_sha256, document_count, chunk_count
                        FROM benchmark_dataset_state WHERE singleton_id = 1
                        """,
                (resultSet, row) -> new State(
                        resultSet.getString("document_vectors_sha256"),
                        resultSet.getInt("document_count"),
                        resultSet.getInt("chunk_count")));
        if (states.size() != 1) return false;
        State state = states.getFirst();
        if (!datasetSha256.equals(state.datasetSha256())
                || state.documentCount() != documentCount || state.chunkCount() != chunkCount) return false;
        Long storedDocuments = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM documents", Long.class);
        Long storedChunks = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_chunks", Long.class);
        return storedDocuments != null && storedDocuments == documentCount
                && storedChunks != null && storedChunks == chunkCount;
    }

    private void insertDocuments(Map<String, List<VectorDocument>> byDocument) {
        List<Map.Entry<String, List<VectorDocument>>> documents = new ArrayList<>(byDocument.entrySet());
        jdbcTemplate.batchUpdate("""
                INSERT INTO documents (id, title, content, metadata_json, created_at)
                VALUES (?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                Map.Entry<String, List<VectorDocument>> entry = documents.get(index);
                List<VectorDocument> chunks = entry.getValue();
                statement.setString(1, entry.getKey());
                statement.setString(2, Objects.toString(chunks.getFirst().metadata().get("title"), ""));
                statement.setString(3, chunks.stream().map(VectorDocument::content).collect(Collectors.joining("\n\n")));
                statement.setString(4, objectMapper.writeValueAsString(commonMetadata(chunks)));
            }

            @Override
            public int getBatchSize() {
                return documents.size();
            }
        });
    }

    private void insertChunks(Map<String, List<VectorDocument>> byDocument) {
        List<SourceChunk> chunks = new ArrayList<>();
        byDocument.forEach((documentId, values) -> {
            for (int sequence = 0; sequence < values.size(); sequence++) {
                chunks.add(new SourceChunk(values.get(sequence), documentId, sequence));
            }
        });
        jdbcTemplate.batchUpdate("""
                INSERT INTO document_chunks (id, document_id, sequence, content, metadata_json)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                SourceChunk chunk = chunks.get(index);
                statement.setString(1, chunk.vector().chunkId());
                statement.setString(2, chunk.documentId());
                statement.setInt(3, chunk.sequence());
                statement.setString(4, chunk.vector().content());
                statement.setString(5, objectMapper.writeValueAsString(chunk.vector().metadata()));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    private Map<String, Object> commonMetadata(List<VectorDocument> chunks) {
        Map<String, Object> common = new LinkedHashMap<>(chunks.getFirst().metadata());
        common.entrySet().removeIf(entry -> chunks.stream()
                .anyMatch(chunk -> !Objects.equals(entry.getValue(), chunk.metadata().get(entry.getKey()))));
        return Map.copyOf(common);
    }

    public record Snapshot(String datasetSha256, int documentCount, int chunkCount, boolean synchronizedNow) {
    }

    private record State(String datasetSha256, int documentCount, int chunkCount) {
    }

    private record SourceChunk(VectorDocument vector, String documentId, int sequence) {
    }
}
