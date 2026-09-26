package com.myapp.benchmark;

import java.util.Map;
import java.util.List;

public record BenchmarkScenario(
        String testId,
        Integer runNumber,
        Integer repetitions,
        String database,
        String engine,
        String indexType,
        int topK,
        int concurrency,
        int warmupIterations,
        int measurementIterations,
        Map<String, Object> searchParameters,
        List<Integer> searchParameterValues
) {
    public BenchmarkScenario {
        runNumber = runNumber == null || runNumber < 1 ? 1 : runNumber;
        repetitions = repetitions == null || repetitions == 0 ? 5 : repetitions;
        if (repetitions < 1 || repetitions > 100) throw new IllegalArgumentException("repetitions must be in [1, 100]");
        if (testId != null && !testId.isBlank() && !testId.matches("T\\d{2}")) {
            throw new IllegalArgumentException("testId must match T followed by two digits");
        }
        if (topK < 1) throw new IllegalArgumentException("topK must be positive");
        if (concurrency < 1) throw new IllegalArgumentException("concurrency must be positive");
        if (warmupIterations < 0) throw new IllegalArgumentException("warmupIterations must not be negative");
        if (measurementIterations < 1) throw new IllegalArgumentException("measurementIterations must be positive");
        searchParameters = searchParameters == null ? Map.of() : Map.copyOf(searchParameters);
        searchParameterValues = searchParameterValues == null ? List.of() : List.copyOf(searchParameterValues);
        if (!searchParameters.isEmpty() && !searchParameterValues.isEmpty()) {
            throw new IllegalArgumentException("Specify either fixed searchParameters or searchParameterValues, not both");
        }
    }

    public BenchmarkScenario measurement(int repetition, Map<String, Object> parameters) {
        return new BenchmarkScenario(testId, runNumber + repetition, 1, database, engine, indexType,
                topK, concurrency, warmupIterations, measurementIterations, parameters, List.of());
    }
}
