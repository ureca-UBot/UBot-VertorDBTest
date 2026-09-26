package com.myapp.domain.vector;

import java.util.*;

/** DB mappings must not silently coerce heterogeneous JSON field types. */
public final class MetadataContract {
    private MetadataContract() { }
    public static void validate(List<VectorDocument> documents, List<BenchmarkQuery> queries) {
        Map<String, String> types = new HashMap<>();
        for (VectorDocument doc : documents) {
            for (var entry : doc.metadata().entrySet()) {
                String type = type(entry.getValue());
                String previous = types.putIfAbsent(entry.getKey(), type);
                if (previous != null && !previous.equals(type)) throw new IllegalArgumentException("Mixed metadata types: " + entry.getKey());
            }
        }
        for (BenchmarkQuery query : queries) {
            for (var entry : query.filter().equals().entrySet()) {
                String declared = types.get(entry.getKey());
                if (declared == null || !declared.equals(type(entry.getValue()))) {
                    throw new IllegalArgumentException("Unknown or incompatible filter field: " + entry.getKey());
                }
            }
        }
    }
    private static String type(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number number) {
            try { new java.math.BigDecimal(number.toString()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Nonfinite metadata number", e); }
            return "number";
        }
        if (value instanceof List<?>) return "array";
        if (value instanceof Map<?, ?>) return "object";
        throw new IllegalArgumentException("Unsupported metadata value");
    }
}
