package com.myapp.tools;

import com.myapp.infrastructure.embedding.OllamaEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class EmbeddingDatasetGeneratorTest {
    @TempDir Path directory;

    @Test
    void reusesVerifiedOutputsButRejectsChangedSourceModelOrOutputWithoutRelabeling() throws Exception {
        var config = fixture();
        var client = client();
        new EmbeddingDatasetGenerator(config, client).run();
        String manifest = Files.readString(config.manifestOutput());
        String originalDocuments = Files.readString(config.documents());
        clearInvocations(client);
        new EmbeddingDatasetGenerator(config, client).run();
        verify(client, never()).embed(anyList());
        assertThat(Files.readString(config.manifestOutput())).isEqualTo(manifest);

        Files.writeString(config.documents(), originalDocuments.replace("hello", "other"));
        assertThatThrownBy(() -> new EmbeddingDatasetGenerator(config, client).run()).hasMessageContaining("inputSha256");
        assertThat(Files.readString(config.manifestOutput())).isEqualTo(manifest);
        Files.writeString(config.documents(), originalDocuments);
        when(client.modelDigest()).thenReturn("digest-new");
        assertThatThrownBy(() -> new EmbeddingDatasetGenerator(config, client).run()).hasMessageContaining("modelDigest");
        when(client.modelDigest()).thenReturn("digest-original");
        Files.writeString(config.documentOutput(), Files.readString(config.documentOutput()).replace("1.0", "0.5"));
        assertThatThrownBy(() -> new EmbeddingDatasetGenerator(config, client).run()).hasMessageContaining("outputSha256");
        assertThat(Files.readString(config.manifestOutput())).isEqualTo(manifest);
    }

    @Test
    void acceptsCompleteLegacyManifestButRejectsMissingProvenance() throws Exception {
        var config = fixture();
        var client = client();
        new EmbeddingDatasetGenerator(config, client).run();
        Files.delete(config.documentOutput().resolveSibling("documents.jsonl.provenance.json"));
        Files.delete(config.queryOutput().resolveSibling("vectors.jsonl.provenance.json"));
        clearInvocations(client);
        new EmbeddingDatasetGenerator(config, client).run();
        verify(client, never()).embed(anyList());
        Files.delete(config.manifestOutput());
        assertThatThrownBy(() -> new EmbeddingDatasetGenerator(config, client).run()).hasMessageContaining("no provenance");
    }

    @Test
    void rejectsPartialWithChangedModelDigestOrTamperedBytesAndLegacyCheckpoint() throws Exception {
        var config = fixture();
        var partial = config.documentOutput().resolveSibling("documents.jsonl.partial");
        var checkpoint = config.documentOutput().resolveSibling("documents.jsonl.checkpoint.json");
        Files.writeString(partial, "{\"id\":\"d1\",\"embedding\":[1,0]}\n");
        var state = new java.util.LinkedHashMap<String, Object>(Map.of("model", config.model(), "modelDigest", "digest-original",
                "dimension", 2, "completedRecords", 1, "inputSha256", hash(config.documents()), "outputSha256", hash(partial)));
        var mapper = JsonMapper.builder().build();
        mapper.writeValue(checkpoint.toFile(), state);
        var client = client();
        when(client.modelDigest()).thenReturn("digest-new");
        assertThatThrownBy(() -> new EmbeddingDatasetGenerator(config, client).run()).hasMessageContaining("modelDigest");
        when(client.modelDigest()).thenReturn("digest-original");
        Files.writeString(partial, "{\"id\":\"d1\",\"embedding\":[0,1]}\n");
        assertThatThrownBy(() -> new EmbeddingDatasetGenerator(config, client).run()).hasMessageContaining("outputSha256");
        state.remove("modelDigest");
        mapper.writeValue(checkpoint.toFile(), state);
        assertThatThrownBy(() -> new EmbeddingDatasetGenerator(config, client).run()).hasMessageContaining("modelDigest");
        verify(client, never()).embed(anyList());
    }

    private EmbeddingDatasetGenerator.Config fixture() throws Exception {
        Path documents = directory.resolve("source-docs.jsonl");
        Path queries = directory.resolve("source-queries.jsonl");
        Files.writeString(documents, "{\"id\":\"d1\",\"content\":\"hello\"}\n");
        Files.writeString(queries, "{\"queryId\":\"q1\",\"query\":\"question\"}\n");
        return new EmbeddingDatasetGenerator.Config("http://unused", "model", 2, 1, documents, queries,
                directory.resolve("documents.jsonl"), directory.resolve("vectors.jsonl"),
                directory.resolve("definitions.jsonl"), directory.resolve("manifest.json"), false);
    }

    private OllamaEmbeddingClient client() {
        var client = mock(OllamaEmbeddingClient.class);
        when(client.modelDigest()).thenReturn("digest-original");
        when(client.embed(anyList())).thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).stream().map(ignored -> new float[]{1, 0}).toList());
        return client;
    }

    private String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
