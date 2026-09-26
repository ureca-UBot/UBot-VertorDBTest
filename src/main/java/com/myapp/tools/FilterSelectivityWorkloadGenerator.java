package com.myapp.tools;

import com.myapp.dataset.QuerySetLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generates cardinality-controlled filter inputs while preserving embedding values. */
public final class FilterSelectivityWorkloadGenerator {
    private static final List<Level> LEVELS = List.of(
            new Level("01", "benchmark_selectivity_01", 0.01),
            new Level("10", "benchmark_selectivity_10", 0.10),
            new Level("50", "benchmark_selectivity_50", 0.50));

    private final ObjectMapper objectMapper;

    FilterSelectivityWorkloadGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static void main(String[] args) {
        Map<String, String> options = options(args);
        Path documentInput = Path.of(required(options, "document-input"));
        Path queryInput = Path.of(required(options, "query-input"));
        Path outputDirectory = Path.of(required(options, "output-directory"));
        boolean overwrite = Boolean.parseBoolean(options.getOrDefault("overwrite", "false"));
        new FilterSelectivityWorkloadGenerator(JsonMapper.builder().build())
                .generate(documentInput, queryInput,
                        options.containsKey("query-vector-input") ? Path.of(options.get("query-vector-input")) : null,
                        outputDirectory, overwrite);
    }

    public Manifest generate(Path documentInput, Path queryInput, Path outputDirectory, boolean overwrite) {
        return generate(documentInput, queryInput, null, outputDirectory, overwrite);
    }

    public Manifest generate(Path documentInput, Path queryInput, Path queryVectorInput, Path outputDirectory, boolean overwrite) {
        try {
            Path documentOutput = outputDirectory.resolve("document-vectors.jsonl");
            Map<Level, Path> queryOutputs = new LinkedHashMap<>();
            for (Level level : LEVELS) queryOutputs.put(level, outputDirectory.resolve("queries-" + level.label() + ".jsonl"));
            List<Path> outputs = new ArrayList<>(queryOutputs.values());
            outputs.add(documentOutput);
            Path vectorOutput = outputDirectory.resolve("query-vectors.jsonl");
            if (queryVectorInput != null) outputs.add(vectorOutput);
            Path manifestPath = outputDirectory.resolve("manifest.json");
            outputs.add(manifestPath);
            for (Path input : queryVectorInput == null ? List.of(documentInput, queryInput) : List.of(documentInput, queryInput, queryVectorInput)) {
                if (outputs.stream().anyMatch(output -> output.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize()))) {
                    throw new IllegalArgumentException("Workload output must not overwrite its source input: " + input);
                }
            }
            Map<String, String> inputHashes = new LinkedHashMap<>();
            inputHashes.put("documentInput", sha256(documentInput));
            inputHashes.put("queryInput", sha256(queryInput));
            if (queryVectorInput != null) inputHashes.put("queryVectorInput", sha256(queryVectorInput));
            if (!overwrite && outputs.stream().anyMatch(Files::exists)) {
                return validateExisting(manifestPath, outputDirectory, inputHashes, outputs);
            }

            // Validate original inline filters before creating definitions that intentionally replace them.
            if (queryVectorInput != null) new QuerySetLoader(objectMapper).load(queryInput, queryVectorInput);
            List<String> documentIds = readDocumentIds(documentInput);
            Map<Level, Set<String>> selectedIds = selectedIds(documentIds);
            Files.createDirectories(outputDirectory);
            writeDocuments(documentInput, documentOutput, selectedIds);
            int filteredQueries = writeQueries(queryInput, queryOutputs);
            if (queryVectorInput != null) writeQueryVectors(queryVectorInput, vectorOutput);

            Map<String, Integer> selectedCounts = new LinkedHashMap<>();
            Map<String, String> queryFiles = new LinkedHashMap<>();
            for (Level level : LEVELS) {
                selectedCounts.put(level.label() + "%", selectedIds.get(level).size());
                queryFiles.put(level.label() + "%", queryOutputs.get(level).toString());
            }
            Map<String, String> outputHashes = new LinkedHashMap<>();
            for (Path output : outputs) if (!output.equals(manifestPath)) outputHashes.put(output.getFileName().toString(), sha256(output));
            if (!inputHashes.get("documentInput").equals(sha256(documentInput))
                    || !inputHashes.get("queryInput").equals(sha256(queryInput))
                    || (queryVectorInput != null && !inputHashes.get("queryVectorInput").equals(sha256(queryVectorInput)))) {
                throw new IllegalStateException("Workload inputs changed while generating outputs");
            }
            Manifest manifest = new Manifest(2, documentIds.size(), filteredQueries, documentOutput.toString(),
                    selectedCounts, queryFiles, "sha256(document-id), nested deterministic cohorts", inputHashes, outputHashes,
                    queryVectorInput == null ? null : vectorOutput.toString());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
            return manifest;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot generate filter-selectivity workload", exception);
        }
    }

    private Manifest validateExisting(Path manifestPath, Path outputDirectory, Map<String, String> inputHashes, List<Path> outputs) {
        if (!Files.isRegularFile(manifestPath)) throw new IllegalStateException("Partial workload has no manifest; choose a new output directory: " + outputDirectory);
        JsonNode stored = objectMapper.readTree(manifestPath.toFile());
        if (stored.path("schemaVersion").asInt(0) != 2) {
            throw new IllegalStateException("Existing workload has no compatible provenance; choose a new output directory: " + outputDirectory);
        }
        Manifest manifest = objectMapper.treeToValue(stored, Manifest.class);
        if (manifest.schemaVersion() != 2 || !inputHashes.equals(manifest.inputHashes())) {
            throw new IllegalStateException("Existing workload provenance does not match source inputs; choose a new output directory: " + outputDirectory);
        }
        Map<String, String> actual = new LinkedHashMap<>();
        for (Path output : outputs) if (!output.equals(manifestPath)) actual.put(output.getFileName().toString(), sha256(output));
        if (!actual.equals(manifest.outputHashes())) throw new IllegalStateException("Existing workload output hashes do not match manifest: " + outputDirectory);
        return manifest;
    }

    private void writeQueryVectors(Path input, Path output) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                ObjectNode vector = (ObjectNode) objectMapper.readTree(line);
                vector.remove("filter");
                writer.write(objectMapper.writeValueAsString(vector));
                writer.newLine();
            }
        }
    }

    private List<String> readDocumentIds(Path input) throws IOException {
        List<String> ids = new ArrayList<>();
        int dimension = -1;
        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = objectMapper.readTree(line);
                ids.add(documentId(node));
                JsonNode embedding = node.has("embedding") ? node.get("embedding") : node.get("vector");
                if (embedding == null || !embedding.isArray() || embedding.isEmpty()) throw new IllegalArgumentException("Document row is missing vector");
                if (dimension == -1) dimension = embedding.size();
                if (dimension != embedding.size()) throw new IllegalArgumentException("Document vector dimensions must match");
                for (JsonNode value : embedding) {
                    if (!value.isNumber() || !Float.isFinite(value.asFloat())) throw new IllegalArgumentException("Document vectors must contain finite numbers");
                }
                JsonNode metadata = node.get("metadata");
                if (metadata != null && !metadata.isNull() && !metadata.isObject()) throw new IllegalArgumentException("Document metadata must be an object");
            }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Document input is empty: " + input);
        if (new LinkedHashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("Document IDs must be unique");
        return List.copyOf(ids);
    }

    private Map<Level, Set<String>> selectedIds(List<String> ids) {
        List<String> ordered = ids.stream()
                .sorted(Comparator.comparing((String value) -> sha256(value)).thenComparing(value -> value))
                .toList();
        Map<Level, Set<String>> selected = new LinkedHashMap<>();
        for (Level level : LEVELS) {
            int count = Math.max(1, (int) Math.round(ids.size() * level.ratio()));
            selected.put(level, Set.copyOf(ordered.subList(0, count)));
        }
        return selected;
    }

    private void writeDocuments(Path input, Path output, Map<Level, Set<String>> selected) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                ObjectNode document = (ObjectNode) objectMapper.readTree(line);
                String id = documentId(document);
                JsonNode metadataNode = document.get("metadata");
                ObjectNode metadata = metadataNode instanceof ObjectNode object
                        ? object
                        : document.putObject("metadata");
                for (Level level : LEVELS) {
                    metadata.put(level.field(), selected.get(level).contains(id) ? "match" : "other");
                }
                writer.write(objectMapper.writeValueAsString(document));
                writer.newLine();
            }
        }
    }

    private static String documentId(JsonNode node) {
        for (String key : List.of("id", "chunkId", "chunk_id")) {
            JsonNode value = node.get(key);
            if (value != null && value.isString() && !value.asString().isBlank()) return value.asString();
        }
        throw new IllegalArgumentException("Document row is missing id");
    }

    private int writeQueries(Path input, Map<Level, Path> outputs) throws IOException {
        Map<Level, BufferedWriter> writers = new LinkedHashMap<>();
        try {
            for (Map.Entry<Level, Path> output : outputs.entrySet()) {
                writers.put(output.getKey(), Files.newBufferedWriter(output.getValue(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            }
            int filteredCount = 0;
            try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    ObjectNode source = (ObjectNode) objectMapper.readTree(line);
                    boolean filtered = source.get("filter") != null && source.get("filter").isObject()
                            && !source.get("filter").isEmpty();
                    if (filtered) filteredCount++;
                    for (Level level : LEVELS) {
                        ObjectNode query = source.deepCopy();
                        if (filtered) {
                            ObjectNode filter = query.putObject("filter");
                            filter.put(level.field(), "match");
                            query.put("query_type", "metadata_filter_" + level.label());
                            query.put("expected_filter_selectivity", level.ratio());
                        }
                        writers.get(level).write(objectMapper.writeValueAsString(query));
                        writers.get(level).newLine();
                    }
                }
            }
            if (filteredCount == 0) throw new IllegalArgumentException("Query input has no metadata-filter queries");
            return filteredCount;
        } finally {
            for (BufferedWriter writer : writers.values()) writer.close();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int size; (size = input.read(buffer)) != -1;) digest.update(buffer, 0, size);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot hash workload input/output: " + path, exception);
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) throw new IllegalArgumentException("Expected --key=value: " + arg);
            int separator = arg.indexOf('=');
            values.put(arg.substring(2, separator), arg.substring(separator + 1));
        }
        return values;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + key);
        return value;
    }

    private record Level(String label, String field, double ratio) {
    }

    public record Manifest(
            int schemaVersion,
            int documentCount,
            int filteredQueryCount,
            String documentFile,
            Map<String, Integer> selectedDocumentCounts,
            Map<String, String> queryFiles,
            String assignmentMethod,
            Map<String, String> inputHashes,
            Map<String, String> outputHashes,
            String queryVectorFile
    ) {
    }
}
