package com.myapp.domain.vector;

import java.util.Map;
import java.math.BigDecimal;

public record VectorFilter(Map<String, Object> equals) {
    public static final VectorFilter NONE = new VectorFilter(Map.of());

    public VectorFilter {
        equals = equals == null ? Map.of() : Map.copyOf(equals);
        equals.forEach((key, value) -> {
            if (key.isBlank()) throw new IllegalArgumentException("Filter field must not be blank");
            if (!(value instanceof String || value instanceof Boolean || value instanceof Number)) {
                throw new IllegalArgumentException("Equality filters support only JSON scalar strings, booleans and numbers: " + key);
            }
            if (value instanceof Number number) decimal(number);
        });
    }

    public boolean isEmpty() {
        return equals.isEmpty();
    }

    /** JSON scalar equality: numeric representation is irrelevant; strings/booleans are never coerced. */
    public boolean matches(Map<String, Object> metadata) {
        return equals.entrySet().stream().allMatch(entry -> {
            Object expected = entry.getValue();
            Object actual = metadata.get(entry.getKey());
            if (expected instanceof Number left && actual instanceof Number right) {
                return decimal(left).compareTo(decimal(right)) == 0;
            }
            return expected.equals(actual);
        });
    }

    private static BigDecimal decimal(Number number) {
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Filter numbers must be finite JSON numbers", exception);
        }
    }
}
