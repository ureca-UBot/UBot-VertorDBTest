package com.myapp.tools;

import com.myapp.dataset.QuerySetLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterSelectivityWorkloadGeneratorTest {
    @TempDir Path directory;

    @Test
    void replacesOriginalInlineFiltersAndValidatesAllCachedInputAndOutputHashes() throws Exception {
        var mapper = JsonMapper.builder().build();
        var generator = new FilterSelectivityWorkloadGenerator(mapper);
        Path documents = directory.resolve("documents.jsonl");
        Path queries = directory.resolve("queries.jsonl");
        Path vectors = directory.resolve("vectors.jsonl");
        Path output = directory.resolve("workload");
        Files.writeString(documents, "{\"id\":\"d1\",\"embedding\":[1,0],\"metadata\":{\"tenant_id\":\"alpha\"}}\n");
        Files.writeString(queries, "{\"queryId\":\"q1\",\"query\":\"hello\",\"filter\":{\"tenant_id\":\"alpha\"}}\n");
        Files.writeString(vectors, "{\"queryId\":\"q1\",\"embedding\":[1,0],\"filter\":{\"tenant_id\":\"alpha\"}}\n");
        var manifest = generator.generate(documents, queries, vectors, output, false);
        assertThat(manifest.inputHashes()).containsKeys("documentInput", "queryInput", "queryVectorInput");
        assertThat(manifest.outputHashes()).containsKeys("document-vectors.jsonl", "query-vectors.jsonl", "queries-01.jsonl", "queries-10.jsonl", "queries-50.jsonl");
        for (String level : new String[]{"01", "10", "50"}) {
            var loaded = new QuerySetLoader(mapper).load(output.resolve("queries-" + level + ".jsonl"), output.resolve("query-vectors.jsonl"));
            assertThat(loaded.getFirst().filter().equals()).containsOnlyKeys("benchmark_selectivity_" + level);
        }
        assertThat(generator.generate(documents, queries, vectors, output, false)).isEqualTo(manifest);
        String original = Files.readString(documents);
        Files.writeString(documents, original.replace("alpha", "other"));
        assertThatThrownBy(() -> generator.generate(documents, queries, vectors, output, false)).hasMessageContaining("provenance");
        Files.writeString(documents, original);
        Files.writeString(output.resolve("queries-01.jsonl"), "{}\n");
        assertThatThrownBy(() -> generator.generate(documents, queries, vectors, output, false)).hasMessageContaining("output hashes");
    }

    @Test
    void refusesLegacyManifestWithoutInputProvenance() throws Exception {
        Path documents = directory.resolve("docs.jsonl");
        Path queries = directory.resolve("queries.jsonl");
        Path output = directory.resolve("workload");
        Files.writeString(documents, "{}\n");
        Files.writeString(queries, "{}\n");
        Files.createDirectories(output);
        Files.writeString(output.resolve("manifest.json"), "{}\n");
        assertThatThrownBy(() -> new FilterSelectivityWorkloadGenerator(JsonMapper.builder().build())
                .generate(documents, queries, output, false)).hasMessageContaining("provenance");
    }
}
