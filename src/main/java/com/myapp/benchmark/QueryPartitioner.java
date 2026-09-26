package com.myapp.benchmark;

import com.myapp.domain.vector.BenchmarkQuery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Creates a deterministic, query-type-stratified calibration/evaluation split. */
public final class QueryPartitioner {
    private QueryPartitioner() {
    }

    public static Partition split(List<BenchmarkQuery> queries, int calibrationCount) {
        if (queries == null || queries.size() < 2) throw new IllegalArgumentException("At least two queries are required");
        if (calibrationCount < 1 || calibrationCount >= queries.size()) {
            throw new IllegalArgumentException("calibrationQueryCount must be between 1 and queryCount - 1");
        }

        Map<String, List<BenchmarkQuery>> byType = new LinkedHashMap<>();
        for (BenchmarkQuery query : queries) {
            byType.computeIfAbsent(query.queryType(), ignored -> new ArrayList<>()).add(query);
        }
        List<Allocation> allocations = new ArrayList<>();
        int assigned = 0;
        for (Map.Entry<String, List<BenchmarkQuery>> entry : byType.entrySet()) {
            double exact = entry.getValue().size() * (double) calibrationCount / queries.size();
            int base = (int) Math.floor(exact);
            allocations.add(new Allocation(entry.getKey(), entry.getValue(), base, exact - base));
            assigned += base;
        }
        allocations.sort(Comparator.comparingDouble(Allocation::remainder).reversed().thenComparing(Allocation::type));
        Map<String, Integer> quotas = new HashMap<>();
        for (Allocation allocation : allocations) quotas.put(allocation.type(), allocation.base());
        for (int index = 0; index < calibrationCount - assigned; index++) {
            Allocation allocation = allocations.get(index % allocations.size());
            quotas.compute(allocation.type(), (ignored, count) -> count + 1);
        }

        Set<String> calibrationIds = new LinkedHashSet<>();
        for (Map.Entry<String, List<BenchmarkQuery>> entry : byType.entrySet()) {
            entry.getValue().stream()
                    .sorted(Comparator.comparing(query -> stableKey(query.queryId())))
                    .limit(quotas.get(entry.getKey()))
                    .map(BenchmarkQuery::queryId)
                    .forEach(calibrationIds::add);
        }
        List<BenchmarkQuery> calibration = queries.stream().filter(query -> calibrationIds.contains(query.queryId())).toList();
        List<BenchmarkQuery> evaluation = queries.stream().filter(query -> !calibrationIds.contains(query.queryId())).toList();
        return new Partition(calibration, evaluation, sha256(calibration), sha256(evaluation));
    }

    private static String stableKey(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(List<BenchmarkQuery> queries) {
        String ids = String.join("\n", queries.stream().map(BenchmarkQuery::queryId).toList());
        return digest(ids.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Allocation(String type, List<BenchmarkQuery> queries, int base, double remainder) {
    }

    public record Partition(
            List<BenchmarkQuery> calibration,
            List<BenchmarkQuery> evaluation,
            String calibrationIdsSha256,
            String evaluationIdsSha256
    ) {
        public Partition {
            calibration = List.copyOf(calibration);
            evaluation = List.copyOf(evaluation);
        }
    }
}
