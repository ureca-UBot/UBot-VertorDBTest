package com.myapp.benchmark;

import com.myapp.dataset.QuerySetLoader;
import com.myapp.domain.vector.BenchmarkQuery;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Verifies a frozen configuration and rejects known overlap with all queries used for selection. */
public final class HoldoutGuard {
    private final ObjectMapper mapper;
    private final QuerySetLoader queryLoader;

    public HoldoutGuard(ObjectMapper mapper) {
        this.mapper = mapper;
        this.queryLoader = new QuerySetLoader(mapper);
    }

    public Map<String, Object> validate(BenchmarkProperties properties, List<BenchmarkScenario> scenarios,
                                        List<BenchmarkQuery> queries, Map<String, Object> indexParameters) {
        if (!holdout(properties)) return Map.of("status", "exploratory-not-independent-validation");
        JsonNode plan = readPlan(properties);
        verifyHash(plan, "documentVectorsSha256", properties.getDocumentVectors());
        verifyHash(plan, "queryVectorsSha256", properties.getQueryVectors());
        verifyHash(plan, "queryDefinitionsSha256", properties.getQueryDefinitions());
        List<BenchmarkQuery> prior = priorQueries(properties, plan);
        if (queries.isEmpty()) throw invalid("holdout query set must not be empty");
        if (scenarios.isEmpty()) throw invalid("at least one fixed scenario is required");
        JsonNode declaredScenarios = plan.get("scenarios");
        if (declaredScenarios == null || !declaredScenarios.isArray() || declaredScenarios.isEmpty()) {
            throw invalid("scenarios must be a non-empty array");
        }
        for (BenchmarkScenario scenario : scenarios) {
            if (scenario.searchParameters().isEmpty() || !scenario.searchParameterValues().isEmpty()) {
                throw invalid("holdout requires fixed searchParameters and forbids a searchParameterValues grid");
            }
            List<JsonNode> matches = new ArrayList<>();
            for (JsonNode candidate : declaredScenarios) {
                if (requiredText(candidate, "testId").equals(scenario.testId())
                        && requiredInt(candidate, "concurrency") == scenario.concurrency()) matches.add(candidate);
            }
            if (matches.size() != 1) throw invalid("expected exactly one planned scenario for "
                    + scenario.testId() + " with concurrency=" + scenario.concurrency());
            JsonNode selected = matches.getFirst();
            for (var field : Map.of("database", scenario.database(), "engine", scenario.engine(),
                    "indexType", scenario.indexType()).entrySet()) {
                if (!requiredText(selected, field.getKey()).equals(field.getValue())) {
                    throw invalid("scenario " + scenario.testId() + " differs from frozen " + field.getKey());
                }
            }
            if (requiredInt(selected, "topK") != scenario.topK()) throw invalid("topK differs from frozen scenario");
            JsonNode fixed = selected.get("searchParameters");
            if (fixed == null || !fixed.isObject() || fixed.isEmpty()
                    || !sameJson(fixed, mapper.valueToTree(scenario.searchParameters()))) {
                throw invalid("searchParameters differ from frozen scenario " + scenario.testId());
            }
            JsonNode grid = selected.get("searchParameterValues");
            if (grid != null && !grid.isEmpty()) throw invalid("planned holdout scenario must not contain a parameter grid");
            JsonNode declaredIndex = selected.has("indexParameters")
                    ? selected.get("indexParameters") : plan.get("indexParameters");
            if (declaredIndex == null || !declaredIndex.isObject()
                    || !sameJson(declaredIndex, mapper.valueToTree(indexParameters))) {
                throw invalid("indexParameters differ from frozen scenario " + scenario.testId());
            }
        }
        rejectOverlap(prior, queries);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("status", "holdout-validated");
        audit.put("planSha256", BenchmarkEnvironmentCollector.sha256(properties.getValidationPlan()));
        audit.put("priorQueryCount", prior.size());
        audit.put("holdoutQueryCount", queries.size());
        audit.put("overlapChecks", List.of("query-id", "normalized-text", "identical-vector"));
        audit.put("priorQueriesWithoutText", prior.stream().filter(query -> normalizedText(query.query()).isEmpty()).count());
        audit.put("holdoutQueriesWithoutText", queries.stream().filter(query -> normalizedText(query.query()).isEmpty()).count());
        audit.put("sourceGroupSemanticOverlap", "Not automatically verifiable: shared source documents, paraphrases, and semantic overlap require a separate source-group audit.");
        audit.put("thresholds", thresholds(plan));
        return Map.copyOf(audit);
    }

    /** Milvus diagnostics use only the prior queries, never the independent holdout set. */
    public List<BenchmarkQuery> calibrationQueries(BenchmarkProperties properties) {
        return holdout(properties) ? priorQueries(properties, readPlan(properties)) : List.of();
    }

    private boolean holdout(BenchmarkProperties properties) {
        if ("exploratory".equals(properties.getMode())) return false;
        if ("holdout".equals(properties.getMode())) return true;
        throw invalid("benchmark.mode must be exploratory or holdout");
    }

    private JsonNode readPlan(BenchmarkProperties properties) {
        if (properties.getValidationPlan() == null) throw invalid("benchmark.validation-plan is required in holdout mode");
        try {
            JsonNode plan = mapper.readTree(properties.getValidationPlan().toFile());
            if (plan == null || !plan.isObject()) throw invalid("validation plan must be a JSON object");
            return plan;
        } catch (RuntimeException exception) {
            throw invalid("cannot read validation plan: " + exception.getMessage());
        }
    }

    private List<BenchmarkQuery> priorQueries(BenchmarkProperties properties, JsonNode plan) {
        Path base = properties.getValidationPlan().toAbsolutePath().normalize().getParent();
        Path vectors = resolve(base, requiredText(plan, "priorQueryVectors"));
        Path definitions = resolve(base, requiredText(plan, "priorQueryDefinitions"));
        verifyHash(plan, "priorQueryVectorsSha256", vectors);
        verifyHash(plan, "priorQueryDefinitionsSha256", definitions);
        return queryLoader.load(definitions, vectors);
    }

    private Path resolve(Path base, String path) {
        Path value = Path.of(path);
        return (value.isAbsolute() ? value : base.resolve(value)).normalize();
    }

    private void verifyHash(JsonNode plan, String field, Path path) {
        String expected = requiredText(plan, field);
        if (!expected.matches("[0-9a-fA-F]{64}")
                || !BenchmarkEnvironmentCollector.sha256(path).equalsIgnoreCase(expected)) {
            throw invalid(field + " does not match the frozen input");
        }
    }

    private void rejectOverlap(List<BenchmarkQuery> prior, List<BenchmarkQuery> queries) {
        Set<String> ids = new HashSet<>();
        Set<String> texts = new HashSet<>();
        Set<String> vectors = new HashSet<>();
        for (BenchmarkQuery query : prior) {
            ids.add(query.queryId());
            String text = normalizedText(query.query());
            if (!text.isEmpty()) texts.add(text);
            vectors.add(vectorFingerprint(query.embedding()));
        }
        for (BenchmarkQuery query : queries) {
            if (ids.contains(query.queryId())) throw invalid("holdout query ID was used for selection: " + query.queryId());
            String text = normalizedText(query.query());
            if (!text.isEmpty() && texts.contains(text)) throw invalid("holdout query text was used for selection: " + query.queryId());
            if (vectors.contains(vectorFingerprint(query.embedding()))) {
                throw invalid("holdout query vector was used for selection: " + query.queryId());
            }
        }
    }

    private static String normalizedText(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    private static String vectorFingerprint(float[] vector) {
        ByteBuffer bytes = ByteBuffer.allocate((vector.length + 1) * Integer.BYTES).putInt(vector.length);
        for (float value : vector) {
            if (!Float.isFinite(value)) throw invalid("query vectors must contain only finite values");
            bytes.putInt(value == 0f ? 0 : Float.floatToIntBits(value));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Map<String, Object> thresholds(JsonNode plan) {
        JsonNode source = plan.has("thresholds") ? plan.get("thresholds") : plan;
        if (source == null || !source.isObject()) throw invalid("thresholds must be an object");
        double recall = requiredDouble(source, "recallMinimum");
        double p95 = requiredDouble(source, "p95MaximumMs");
        double errorRate = requiredDouble(source, "errorRateMaximum");
        JsonNode ramValue = source.get("ramMaximumBytes");
        long ram;
        try {
            if (ramValue == null || !ramValue.isNumber()) throw new NumberFormatException();
            ram = new BigDecimal(ramValue.asString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid("ramMaximumBytes must be a positive integer");
        }
        if (recall <= 0 || recall > 1 || p95 <= 0 || ram <= 0 || errorRate < 0 || errorRate > 1) {
            throw invalid("thresholds require recallMinimum in (0,1], positive p95/RAM, and errorRateMaximum in [0,1]");
        }
        return Map.of("recallMinimum", recall, "p95MaximumMs", p95,
                "ramMaximumBytes", ram, "errorRateMaximum", errorRate);
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) throw invalid("missing string field " + field);
        return value.asString();
    }

    private static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        try {
            if (value == null || !value.isNumber()) throw new NumberFormatException();
            int result = new BigDecimal(value.asString()).intValueExact();
            if (result < 1) throw new NumberFormatException();
            return result;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(field + " must be a positive integer");
        }
    }

    private static double requiredDouble(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) throw invalid("missing finite numeric field " + field);
        return value.asDouble();
    }

    private static boolean sameJson(JsonNode expected, JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) {
            return new BigDecimal(expected.asString()).compareTo(new BigDecimal(actual.asString())) == 0;
        }
        if (expected.isObject() && actual.isObject()) {
            if (expected.size() != actual.size()) return false;
            for (var field : expected.properties()) {
                JsonNode value = actual.get(field.getKey());
                if (value == null || !sameJson(field.getValue(), value)) return false;
            }
            return true;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) return false;
            for (int index = 0; index < expected.size(); index++) {
                if (!sameJson(expected.get(index), actual.get(index))) return false;
            }
            return true;
        }
        return expected.equals(actual);
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid holdout validation: " + reason);
    }
}
