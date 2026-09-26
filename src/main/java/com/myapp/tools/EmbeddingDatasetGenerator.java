package com.myapp.tools;

import com.myapp.infrastructure.embedding.OllamaEmbeddingClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Generates immutable benchmark JSONL files from the bundled corpus and a local Ollama model.
 * Partial outputs are resumable and are moved to their final names only after full validation.
 */
public final class EmbeddingDatasetGenerator {
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Config config;
    private final OllamaEmbeddingClient client;
    private String modelDigest;
    private MessageDigest partialDigest;

    private EmbeddingDatasetGenerator(Config config) {
        this.config = config;
        this.client = new OllamaEmbeddingClient(config.ollamaUrl(), config.model(), objectMapper);
    }

    EmbeddingDatasetGenerator(Config config, OllamaEmbeddingClient client) {
        this.config = config;
        this.client = client;
    }

    public static void main(String[] args) {
        Config config = Config.parse(args);
        new EmbeddingDatasetGenerator(config).run();
    }

    void run() {
        System.out.printf("Embedding model=%s dimension=%d batch=%d endpoint=%s%n",
                config.model(), config.dimension(), config.batchSize(), config.ollamaUrl());
        modelDigest = client.modelDigest();
        if (modelDigest == null || modelDigest.isBlank() || "unavailable".equals(modelDigest)) {
            throw new IllegalStateException("A verified model digest is required to generate or reuse embeddings");
        }
        validatePaths();
        validateSource(config.documents(), RecordType.DOCUMENT);
        validateSource(config.queries(), RecordType.QUERY);
        // Refuse stale reuse before generating either output or rewriting any manifest.
        if (!config.overwrite()) {
            preflightExisting(config.documents(), config.documentOutput(), RecordType.DOCUMENT);
            preflightExisting(config.queries(), config.queryOutput(), RecordType.QUERY);
        }
        boolean reusedCompleteDataset = !config.overwrite() && Files.isRegularFile(config.documentOutput())
                && Files.isRegularFile(config.queryOutput()) && manifestMatchesCurrentDataset();
        GenerationResult documents = generate(config.documents(), config.documentOutput(), RecordType.DOCUMENT);
        GenerationResult queries = generate(config.queries(), config.queryOutput(), RecordType.QUERY);
        copyQueryDefinitions();
        if (reusedCompleteDataset) {
            System.out.println("Verified existing embedding dataset; preserved original manifest: " + config.manifestOutput());
            return;
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("generatedAt", Instant.now().toString());
        manifest.put("provider", "ollama");
        manifest.put("endpoint", config.ollamaUrl());
        manifest.put("model", config.model());
        manifest.put("modelDigest", modelDigest);
        manifest.put("dimension", config.dimension());
        manifest.put("batchSize", config.batchSize());
        manifest.put("queryInstruction", "none");
        manifest.put("documentInput", fileMetadata(config.documents()));
        manifest.put("queryInput", fileMetadata(config.queries()));
        manifest.put("documentOutput", documents.toMap());
        manifest.put("queryOutput", queries.toMap());
        writeJsonAtomically(config.manifestOutput(), manifest);
        System.out.printf("Completed: documents=%d queries=%d manifest=%s%n",
                documents.records(), queries.records(), config.manifestOutput());
    }

    private GenerationResult generate(Path source, Path output, RecordType type) {
        requireReadableFile(source);
        String inputHash = sha256(source);
        long expectedRecords = countNonBlankLines(source);
        Path partial = sibling(output, output.getFileName() + ".partial");
        Path checkpoint = sibling(output, output.getFileName() + ".checkpoint.json");
        Path provenance = sibling(output, output.getFileName() + ".provenance.json");

        if (config.overwrite()) {
            deleteIfExists(output);
            deleteIfExists(partial);
            deleteIfExists(checkpoint);
            deleteIfExists(provenance);
        }
        if (Files.exists(output)) {
            if (Files.exists(partial) || Files.exists(checkpoint)) {
                throw new IllegalStateException("Final and partial embedding outputs coexist: " + output);
            }
            GenerationResult existing = inspect(output, type, expectedRecords);
            validateCompletedProvenance(source, output, type, existing);
            System.out.printf("Already complete: %s (%d records)%n", output, existing.records());
            return existing;
        }

        createParent(output);
        partialDigest = newDigest();
        if (Files.exists(partial)) updateDigest(partialDigest, partial);
        long completed = preparePartial(source, partial, checkpoint, type, inputHash);
        long started = System.nanoTime();
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(
                     new DigestOutputStream(Files.newOutputStream(partial, StandardOpenOption.CREATE,
                             StandardOpenOption.APPEND), partialDigest), StandardCharsets.UTF_8))) {
            skipNonBlank(reader, completed);
            List<InputRecord> batch = new ArrayList<>(config.batchSize());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                batch.add(type.read(objectMapper.readTree(line), objectMapper));
                if (batch.size() == config.batchSize()) {
                    completed = embedAndWrite(batch, writer, completed, checkpoint, source, inputHash);
                    progress(type, completed, expectedRecords, started);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                completed = embedAndWrite(batch, writer, completed, checkpoint, source, inputHash);
                progress(type, completed, expectedRecords, started);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot generate embeddings from " + source, exception);
        }
        if (completed != expectedRecords) {
            throw new IllegalStateException("Generated record count mismatch for " + source
                    + ": expected " + expectedRecords + " but got " + completed);
        }
        if (!inputHash.equals(sha256(source)) || !modelDigest.equals(client.modelDigest())) {
            throw new IllegalStateException("Source input or model digest changed during embedding generation");
        }
        GenerationResult generated = inspect(partial, type, expectedRecords);
        // Publish provenance first, so a crash after publishing the final file remains recoverable.
        writeJsonAtomically(provenance, Map.of("model", config.model(), "modelDigest", modelDigest,
                "dimension", config.dimension(), "recordType", type.name(), "inputSha256", inputHash,
                "outputSha256", generated.sha256(), "records", generated.records()));
        moveAtomically(partial, output);
        deleteIfExists(checkpoint);
        return inspect(output, type, expectedRecords);
    }

    private long preparePartial(
            Path source,
            Path partial,
            Path checkpoint,
            RecordType type,
            String inputHash
    ) {
        if (!Files.exists(partial)) {
            if (Files.exists(checkpoint)) throw new IllegalStateException("Checkpoint exists without partial output: " + checkpoint);
            writeCheckpoint(checkpoint, source, inputHash, 0);
            return 0;
        }
        if (!Files.exists(checkpoint)) throw new IllegalStateException("Partial output has no checkpoint: " + partial);
        JsonNode state = readJson(checkpoint);
        requireCheckpoint(state, "model", config.model());
        requireCheckpoint(state, "modelDigest", modelDigest);
        requireCheckpoint(state, "inputSha256", inputHash);
        requireCheckpoint(state, "outputSha256", digestSnapshot(partialDigest));
        if (state.path("dimension").asInt() != config.dimension()) {
            throw new IllegalStateException("Partial output dimension does not match current configuration");
        }
        long completed = validatePartial(source, partial, type);
        if (state.path("completedRecords").asLong(-1) != completed) {
            throw new IllegalStateException("Checkpoint count does not match partial output: " + partial);
        }
        System.out.printf("Resuming %s at record %d%n", partial, completed);
        return completed;
    }

    private long embedAndWrite(
            List<InputRecord> batch,
            BufferedWriter writer,
            long completed,
            Path checkpoint,
            Path source,
            String inputHash
    ) throws IOException {
        List<float[]> vectors = embedResilient(batch.stream().map(InputRecord::text).toList());
        for (int i = 0; i < batch.size(); i++) {
            float[] vector = vectors.get(i);
            validateVector(vector);
            Map<String, Object> row = new LinkedHashMap<>(batch.get(i).output());
            row.put("embedding", vector);
            writer.write(objectMapper.writeValueAsString(row));
            writer.newLine();
        }
        writer.flush();
        long next = completed + batch.size();
        writeCheckpoint(checkpoint, source, inputHash, next);
        return next;
    }

    private List<float[]> embedResilient(List<String> texts) {
        try {
            return client.embed(texts);
        } catch (IllegalStateException exception) {
            if (texts.size() <= 16) throw exception;
            int middle = texts.size() / 2;
            System.err.printf("Embedding batch of %d failed after retries; splitting into %d and %d.%n",
                    texts.size(), middle, texts.size() - middle);
            List<float[]> vectors = new ArrayList<>(texts.size());
            vectors.addAll(embedResilient(texts.subList(0, middle)));
            vectors.addAll(embedResilient(texts.subList(middle, texts.size())));
            return List.copyOf(vectors);
        }
    }

    private void writeCheckpoint(Path checkpoint, Path source, String inputHash, long completed) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("source", source.toString());
        state.put("inputSha256", inputHash);
        state.put("model", config.model());
        state.put("modelDigest", modelDigest);
        state.put("outputSha256", digestSnapshot(partialDigest));
        state.put("dimension", config.dimension());
        state.put("completedRecords", completed);
        writeJsonAtomically(checkpoint, state);
    }

    private long validatePartial(Path source, Path partial, RecordType type) {
        long count = 0;
        try (BufferedReader inputs = Files.newBufferedReader(source, StandardCharsets.UTF_8);
             BufferedReader outputs = Files.newBufferedReader(partial, StandardCharsets.UTF_8)) {
            String outputLine;
            while ((outputLine = outputs.readLine()) != null) {
                if (outputLine.isBlank()) continue;
                String inputLine = nextNonBlank(inputs);
                if (inputLine == null) throw new IllegalStateException("Partial output is longer than source: " + partial);
                JsonNode input = objectMapper.readTree(inputLine);
                JsonNode output = objectMapper.readTree(outputLine);
                String expectedId = type.id(input);
                String actualId = type.id(output);
                if (!expectedId.equals(actualId)) {
                    throw new IllegalStateException("Partial output order mismatch at record " + count);
                }
                Map<String, Object> expectedFields = type.read(input, objectMapper).output();
                for (Map.Entry<String, Object> field : expectedFields.entrySet()) {
                    JsonNode expected = objectMapper.valueToTree(field.getValue());
                    if (!expected.equals(output.get(field.getKey()))) {
                        throw new IllegalStateException("Embedding output does not match source field " + field.getKey() + " at record " + count);
                    }
                }
                validateVector(readVector(output));
                count++;
            }
            return count;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot validate partial output " + partial, exception);
        }
    }

    private GenerationResult inspect(Path output, RecordType type, long expectedRecords) {
        long records = 0;
        Set<String> ids = new HashSet<>();
        double minNorm = Double.POSITIVE_INFINITY;
        double maxNorm = 0;
        try (BufferedReader reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode row = objectMapper.readTree(line);
                String id = type.id(row);
                if (id == null || id.isBlank() || !ids.add(id)) throw new IllegalStateException("Missing or duplicate output id: " + id);
                float[] vector = readVector(row);
                validateVector(vector);
                double norm = norm(vector);
                minNorm = Math.min(minNorm, norm);
                maxNorm = Math.max(maxNorm, norm);
                records++;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect generated output " + output, exception);
        }
        if (records != expectedRecords) {
            throw new IllegalStateException("Existing output count mismatch for " + output
                    + ": expected " + expectedRecords + " but got " + records);
        }
        return new GenerationResult(output, records, sha256(output), minNorm, maxNorm);
    }

    private void copyQueryDefinitions() {
        Path destination = config.queryDefinitionsOutput();
        String sourceHash = sha256(config.queries());
        if (Files.exists(destination) && !config.overwrite()) {
            if (!sourceHash.equals(sha256(destination))) {
                throw new IllegalStateException("Query definitions output exists with different content: " + destination);
            }
            return;
        }
        createParent(destination);
        Path temporary = sibling(destination, destination.getFileName() + ".tmp");
        try {
            Files.copy(config.queries(), temporary, StandardCopyOption.REPLACE_EXISTING);
            moveAtomically(temporary, destination);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot copy query definitions", exception);
        }
    }

    private Map<String, Object> fileMetadata(Path path) {
        return Map.of("path", path.toString(), "records", countNonBlankLines(path), "sha256", sha256(path));
    }

    private void validateVector(float[] vector) {
        if (vector.length != config.dimension()) {
            throw new IllegalStateException("Expected vector dimension " + config.dimension() + " but got " + vector.length);
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Embedding contains a non-finite value");
        }
        if (norm(vector) == 0) throw new IllegalStateException("Embedding vector must not be zero");
    }

    private float[] readVector(JsonNode node) {
        JsonNode embedding = node.get("embedding");
        if (embedding == null || !embedding.isArray()) throw new IllegalStateException("Output record has no embedding");
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            if (!embedding.get(i).isNumber()) throw new IllegalStateException("Embedding values must be numbers");
            vector[i] = embedding.get(i).asFloat();
        }
        return vector;
    }

    private void progress(RecordType type, long completed, long total, long started) {
        double seconds = Math.max(0.001, (System.nanoTime() - started) / 1_000_000_000.0);
        double rate = completed / seconds;
        System.out.printf("%s %,d/%,d (%.1f%%, %.1f records/s)%n",
                type.name().toLowerCase(), completed, total, completed * 100.0 / total, rate);
    }

    private JsonNode readJson(Path path) {
        return objectMapper.readTree(path.toFile());
    }

    private void requireCheckpoint(JsonNode state, String field, String expected) {
        JsonNode value = state.get(field);
        if (value == null || !expected.equals(value.asString())) {
            throw new IllegalStateException("Embedding provenance has a missing or different " + field);
        }
    }

    private void writeJsonAtomically(Path target, Object value) {
        createParent(target);
        Path temporary = sibling(target, target.getFileName() + ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            moveAtomically(temporary, target);
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    private static void moveAtomically(Path source, Path target) {
        try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot move " + source + " to " + target, exception);
        }
    }

    private static void createParent(Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create output directory for " + path, exception);
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot delete generated output " + path, exception);
        }
    }

    private static void requireReadableFile(Path path) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("Input file is not readable: " + path);
        }
    }

    private static long countNonBlankLines(Path path) {
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).count();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot count records in " + path, exception);
        }
    }

    private static void skipNonBlank(BufferedReader reader, long records) throws IOException {
        for (long skipped = 0; skipped < records; ) {
            String line = reader.readLine();
            if (line == null) throw new IllegalStateException("Partial output is longer than input");
            if (!line.isBlank()) skipped++;
        }
    }

    private static String nextNonBlank(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) if (!line.isBlank()) return line;
        return null;
    }

    private static double norm(float[] vector) {
        double squared = 0;
        for (float value : vector) squared += value * value;
        return Math.sqrt(squared);
    }

    private static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot hash " + path, exception);
        }
    }

    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static void updateDigest(MessageDigest digest, Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        } catch (IOException exception) { throw new IllegalStateException("Cannot hash partial output " + path, exception); }
    }

    private static String digestSnapshot(MessageDigest digest) {
        try { return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest()); }
        catch (CloneNotSupportedException exception) { throw new IllegalStateException("SHA-256 snapshots are unavailable", exception); }
    }

    private void validatePaths() {
        List<Path> inputs = List.of(config.documents().toAbsolutePath().normalize(), config.queries().toAbsolutePath().normalize());
        List<Path> targets = new ArrayList<>(List.of(config.documentOutput(), config.queryOutput(), config.queryDefinitionsOutput(), config.manifestOutput()));
        for (Path output : List.of(config.documentOutput(), config.queryOutput())) {
            for (String suffix : List.of(".partial", ".checkpoint.json", ".checkpoint.json.tmp", ".provenance.json", ".provenance.json.tmp")) {
                targets.add(sibling(output, output.getFileName() + suffix));
            }
        }
        targets.add(sibling(config.queryDefinitionsOutput(), config.queryDefinitionsOutput().getFileName() + ".tmp"));
        targets.add(sibling(config.manifestOutput(), config.manifestOutput().getFileName() + ".tmp"));
        List<Path> outputs = targets.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        if (new HashSet<>(outputs).size() != outputs.size() || outputs.stream().anyMatch(inputs::contains)) {
            throw new IllegalArgumentException("Embedding output paths must be distinct from each other and source inputs");
        }
    }

    private boolean manifestMatchesCurrentDataset() {
        if (!Files.isRegularFile(config.manifestOutput())) return false;
        JsonNode manifest = readJson(config.manifestOutput());
        return config.model().equals(manifest.path("model").asString())
                && modelDigest.equals(manifest.path("modelDigest").asString())
                && config.dimension() == manifest.path("dimension").asInt()
                && sha256(config.documents()).equals(manifest.path("documentInput").path("sha256").asString())
                && sha256(config.queries()).equals(manifest.path("queryInput").path("sha256").asString())
                && sha256(config.documentOutput()).equals(manifest.path("documentOutput").path("sha256").asString())
                && sha256(config.queryOutput()).equals(manifest.path("queryOutput").path("sha256").asString());
    }

    private void validateSource(Path source, RecordType type) {
        requireReadableFile(source);
        Set<String> ids = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank()) continue;
                InputRecord record = type.read(objectMapper.readTree(line), objectMapper);
                if (!ids.add(record.id())) throw new IllegalArgumentException("Duplicate embedding source id: " + record.id());
            }
        } catch (IOException exception) { throw new IllegalStateException("Cannot validate source " + source, exception); }
        if (ids.isEmpty()) throw new IllegalArgumentException("Embedding source is empty: " + source);
    }

    private void preflightExisting(Path source, Path output, RecordType type) {
        Path partial = sibling(output, output.getFileName() + ".partial");
        Path checkpoint = sibling(output, output.getFileName() + ".checkpoint.json");
        if (Files.exists(output)) {
            if (Files.exists(partial) || Files.exists(checkpoint)) throw new IllegalStateException("Final and partial embedding outputs coexist: " + output);
            validateCompletedProvenance(source, output, type, inspect(output, type, countNonBlankLines(source)));
        } else if (Files.exists(partial) || Files.exists(checkpoint)) {
            partialDigest = newDigest();
            if (Files.exists(partial)) updateDigest(partialDigest, partial);
            preparePartial(source, partial, checkpoint, type, sha256(source));
        }
    }

    private void validateCompletedProvenance(Path source, Path output, RecordType type, GenerationResult result) {
        Path provenance = sibling(output, output.getFileName() + ".provenance.json");
        if (Files.isRegularFile(provenance)) {
            JsonNode state = readJson(provenance);
            requireCheckpoint(state, "model", config.model());
            requireCheckpoint(state, "modelDigest", modelDigest);
            requireCheckpoint(state, "inputSha256", sha256(source));
            requireCheckpoint(state, "outputSha256", result.sha256());
            requireCheckpoint(state, "recordType", type.name());
            if (state.path("dimension").asInt() != config.dimension() || state.path("records").asLong(-1) != result.records()) {
                throw new IllegalStateException("Completed output provenance has different dimension or record count: " + output);
            }
            validatePartial(source, output, type);
            return;
        }
        // Existing manifests already contain all required provenance. Older unproven files must
        // use a new output location or explicit overwrite; never relabel them with today's inputs.
        if (!Files.isRegularFile(config.manifestOutput())) throw new IllegalStateException("Completed output has no provenance: " + output);
        JsonNode manifest = readJson(config.manifestOutput());
        requireCheckpoint(manifest, "model", config.model());
        requireCheckpoint(manifest, "modelDigest", modelDigest);
        String prefix = type == RecordType.DOCUMENT ? "document" : "query";
        requireCheckpoint(manifest.path(prefix + "Input"), "sha256", sha256(source));
        requireCheckpoint(manifest.path(prefix + "Output"), "sha256", result.sha256());
        if (manifest.path("dimension").asInt() != config.dimension()
                || manifest.path(prefix + "Input").path("records").asLong(-1) != result.records()
                || manifest.path(prefix + "Output").path("records").asLong(-1) != result.records()) {
            throw new IllegalStateException("Existing manifest dimension or record count differs: " + output);
        }
        validatePartial(source, output, type);
    }

    private static Path sibling(Path path, String fileName) {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) throw new IllegalArgumentException("Output path must have a parent: " + path);
        return parent.resolve(fileName);
    }

    private record InputRecord(String id, String text, Map<String, Object> output) {
        private InputRecord {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Embedding input id must not be blank");
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding input text must not be blank: " + id);
        }
    }

    private enum RecordType {
        DOCUMENT {
            @Override
            InputRecord read(JsonNode node, ObjectMapper objectMapper) {
                String id = text(node, "id", "chunkId", "chunk_id");
                String documentId = text(node, "documentId", "document_id");
                String chunkId = text(node, "chunkId", "chunk_id", "id");
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("id", id);
                output.put("documentId", documentId == null ? id : documentId);
                output.put("chunkId", chunkId == null ? id : chunkId);
                output.put("content", text(node, "content", "text"));
                output.put("metadata", metadata(node, objectMapper));
                return new InputRecord(id, text(node, "content", "text"), output);
            }
        },
        QUERY {
            @Override
            InputRecord read(JsonNode node, ObjectMapper objectMapper) {
                String id = text(node, "queryId", "query_id", "id");
                return new InputRecord(id, text(node, "query", "text"), new LinkedHashMap<>(Map.of("queryId", id)));
            }
        };

        abstract InputRecord read(JsonNode node, ObjectMapper objectMapper);

        String id(JsonNode node) {
            return this == DOCUMENT
                    ? text(node, "id", "chunkId", "chunk_id")
                    : text(node, "queryId", "query_id", "id");
        }

        private static String text(JsonNode node, String... fields) {
            for (String field : fields) {
                JsonNode value = node.get(field);
                if (value != null && !value.isNull() && !value.asString().isBlank()) return value.asString();
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> metadata(JsonNode node, ObjectMapper objectMapper) {
            JsonNode nested = node.get("metadata");
            if (nested != null && nested.isObject()) return objectMapper.treeToValue(nested, Map.class);
            Map<String, Object> source = objectMapper.treeToValue(node, Map.class);
            Map<String, Object> metadata = new LinkedHashMap<>(source);
            List.of("id", "documentId", "document_id", "chunkId", "chunk_id", "content", "text", "embedding", "vector")
                    .forEach(metadata::remove);
            return Map.copyOf(metadata);
        }
    }

    private record GenerationResult(Path path, long records, String sha256, double minL2Norm, double maxL2Norm) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("path", path.toString());
            values.put("records", records);
            values.put("sha256", sha256);
            values.put("minL2Norm", minL2Norm);
            values.put("maxL2Norm", maxL2Norm);
            return Map.copyOf(values);
        }
    }

    record Config(
            String ollamaUrl,
            String model,
            int dimension,
            int batchSize,
            Path documents,
            Path queries,
            Path documentOutput,
            Path queryOutput,
            Path queryDefinitionsOutput,
            Path manifestOutput,
            boolean overwrite
    ) {
        static Config parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String argument : args) {
                if (!argument.startsWith("--") || !argument.contains("=")) {
                    throw new IllegalArgumentException("Arguments must use --name=value: " + argument);
                }
                String[] parts = argument.substring(2).split("=", 2);
                values.put(parts[0], parts[1]);
            }
            int dimension = integer(values, "dimension", 1024);
            int batchSize = integer(values, "batch-size", 128);
            if (dimension < 1 || batchSize < 1) throw new IllegalArgumentException("dimension and batch-size must be positive");
            return new Config(
                    values.getOrDefault("ollama-url", "http://localhost:11434"),
                    values.getOrDefault("model", "bge-m3:latest"),
                    dimension,
                    batchSize,
                    Path.of(values.getOrDefault("documents", "data/documents_10000.jsonl")),
                    Path.of(values.getOrDefault("queries", "data/queries_300.jsonl")),
                    Path.of(values.getOrDefault("document-output", "data/embeddings/document-vectors.jsonl")),
                    Path.of(values.getOrDefault("query-output", "data/embeddings/query-vectors.jsonl")),
                    Path.of(values.getOrDefault("query-definitions-output", "data/queries/queries.jsonl")),
                    Path.of(values.getOrDefault("manifest-output", "data/embeddings/embedding-manifest.json")),
                    Boolean.parseBoolean(values.getOrDefault("overwrite", "false"))
            );
        }

        private static int integer(Map<String, String> values, String name, int fallback) {
            return Integer.parseInt(values.getOrDefault(name, Integer.toString(fallback)));
        }
    }
}
