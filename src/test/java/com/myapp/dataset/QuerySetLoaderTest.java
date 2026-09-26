package com.myapp.dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuerySetLoaderTest {
    @TempDir
    Path directory;

    @Test
    void joinsDefinitionsAndPrecomputedVectorsByQueryId() throws IOException {
        Path definitions = directory.resolve("queries.jsonl");
        Path vectors = directory.resolve("query-vectors.jsonl");
        Files.writeString(definitions, "{\"queryId\":\"q-1\",\"query\":\"hello\",\"filter\":{\"tenant\":\"a\"}}\n");
        Files.writeString(vectors, "{\"queryId\":\"q-1\",\"embedding\":[0.1,0.2]}\n");

        var queries = new QuerySetLoader(JsonMapper.builder().build()).load(definitions, vectors);

        assertThat(queries).hasSize(1);
        assertThat(queries.getFirst().query()).isEqualTo("hello");
        assertThat(queries.getFirst().filter().equals()).containsEntry("tenant", "a");
    }

    @Test
    void rejectsInlineFilterConflictInsteadOfSilentlyChangingWorkload() throws IOException {
        Path definitions = directory.resolve("queries.jsonl");
        Path vectors = directory.resolve("vectors.jsonl");
        Files.writeString(definitions, "{\"queryId\":\"q1\",\"filter\":{\"benchmark_selectivity_01\":\"match\"}}\n");
        Files.writeString(vectors, "{\"queryId\":\"q1\",\"embedding\":[1,0],\"filter\":{\"tenant\":\"old\"}}\n");
        assertThatThrownBy(() -> new QuerySetLoader(JsonMapper.builder().build()).load(definitions, vectors))
                .hasRootCauseMessage("Inline filter conflicts with authoritative query definition: q1");
    }

    @Test
    void requiresUniqueMatchingIdsAndValidVectors() throws IOException {
        Path definitions = directory.resolve("queries.jsonl");
        Path vectors = directory.resolve("vectors.jsonl");
        var loader = new QuerySetLoader(JsonMapper.builder().build());
        Files.writeString(definitions, "{\"queryId\":\"q1\"}\n{\"queryId\":\"q2\"}\n");
        Files.writeString(vectors, "{\"queryId\":\"q1\",\"embedding\":[1,0]}\n");
        assertThatThrownBy(() -> loader.load(definitions, vectors)).hasMessageContaining("IDs must match exactly");
        Files.writeString(vectors, "{\"queryId\":\"q1\",\"embedding\":[1,0]}\n{\"queryId\":\"q1\",\"embedding\":[0,1]}\n");
        assertThatThrownBy(() -> loader.load(definitions, vectors)).hasRootCauseMessage("Duplicate query vector id: q1");
        Files.writeString(definitions, "{\"queryId\":\"q1\"}\n{\"queryId\":\"q1\"}\n");
        assertThatThrownBy(() -> loader.load(definitions, vectors)).hasMessageContaining("Duplicate query definition");
    }

    @Test
    void acceptsSameDefinitionsAndVectorsFileAndPreservesSyntheticFlag() throws IOException {
        Path combined = directory.resolve("combined.jsonl");
        Files.writeString(combined, "{\"queryId\":\"q1\",\"embedding\":[1,0],\"filter\":{\"tenant\":\"a\"},\"synthetic\":true}\n");
        var queries = new QuerySetLoader(JsonMapper.builder().build()).load(combined, combined);
        assertThat(queries.getFirst().synthetic()).isTrue();
        assertThat(queries.getFirst().filter().equals()).containsEntry("tenant", "a");
    }
}
