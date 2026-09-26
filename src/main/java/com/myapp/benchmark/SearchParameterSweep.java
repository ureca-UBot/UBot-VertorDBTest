package com.myapp.benchmark;

import com.myapp.port.VectorIndexManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Expands a grid without consulting Recall or choosing a quality target. */
final class SearchParameterSweep {
    private SearchParameterSweep() { }

    static List<Map<String, Object>> parameters(BenchmarkScenario scenario, VectorIndexManager manager,
                                                 List<Integer> defaults) {
        String key = manager.searchParameterName();
        if (!scenario.searchParameters().isEmpty()) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("This index has no configurable search parameter");
            }
            if (!scenario.searchParameters().keySet().equals(java.util.Set.of(key))) {
                throw new IllegalArgumentException("searchParameters must contain only " + key);
            }
            int value = integerParameter(key, scenario.searchParameters().get(key));
            validate(value, scenario, manager);
            // Record exactly the integer the adapter receives, not an unchecked caller-supplied map.
            return List.of(Map.of(key, value));
        }
        if (key == null || key.isBlank()) {
            if (!scenario.searchParameterValues().isEmpty()) {
                throw new IllegalArgumentException("This index has no sweep parameter");
            }
            return List.of(Map.of("k", scenario.topK()));
        }
        List<Integer> values = scenario.searchParameterValues().isEmpty() ? defaults : scenario.searchParameterValues();
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("Search parameter grid must not be empty");
        for (Integer value : values) {
            if (value == null) throw new IllegalArgumentException("Search parameter grid must not contain null");
            validate(value, scenario, manager);
        }
        return values.stream().distinct().sorted().map(value -> Map.<String, Object>of(key, value)).toList();
    }

    /** A reproducible run-specific order avoids always measuring large values after small ones. */
    static List<Map<String, Object>> parametersForRun(List<Map<String, Object>> grid, int runNumber) {
        if (runNumber < 1) throw new IllegalArgumentException("runNumber must be positive");
        List<Map<String, Object>> ordered = new ArrayList<>(grid);
        Collections.shuffle(ordered, new Random(0x5EED5EEDL ^ runNumber));
        return List.copyOf(ordered);
    }

    private static int integerParameter(String key, Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        try {
            // intValue/doubleValue alone silently round high-precision fractional JSON numbers.
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer in the 32-bit range", exception);
        }
    }

    private static void validate(int value, BenchmarkScenario scenario, VectorIndexManager manager) {
        int minimum = manager.minimumSearchParameter(scenario.topK());
        int maximum = manager.maximumSearchParameter();
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(manager.searchParameterName() + "=" + value
                    + " is outside the supported parameter range " + minimum + ".." + maximum);
        }
    }
}
