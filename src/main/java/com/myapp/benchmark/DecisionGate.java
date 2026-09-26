package com.myapp.benchmark;

import java.util.*;

/** Predeclared gates; missing evidence is not a pass. Raw measurements are never filtered. */
public final class DecisionGate {
    private DecisionGate() { }
    public static Verdict evaluate(List<BenchmarkResult> results) {
        Set<String> reasons = new LinkedHashSet<>();
        long builds = results.stream().filter(r -> r.audit().rebuilt()).map(r -> r.audit().buildId()).distinct().count();
        if (builds < 5) reasons.add("fewer-than-5-independent-rebuilds");
        for (BenchmarkResult r : results) {
            if (!"holdout".equals(r.environment().get("mode"))) reasons.add("exploratory-not-independent-holdout");
            Map<?, ?> validation = r.environment().get("validation") instanceof Map<?, ?> m ? m : Map.of();
            if (!"holdout-validated".equals(validation.get("status"))) reasons.add("holdout-plan-unverified");
            Map<?, ?> thresholds = validation.get("thresholds") instanceof Map<?, ?> m ? m : Map.of();
            double recall = number(thresholds, "recallMinimum", .95);
            double p95 = number(thresholds, "p95MaximumMs", 30);
            double ram = number(thresholds, "ramMaximumBytes", 2L * 1024 * 1024 * 1024);
            double errorRate = number(thresholds, "errorRateMaximum", 0);
            if (!r.audit().rebuilt()) reasons.add("index-not-rebuilt");
            if (!r.audit().warmupStable()) reasons.add("warmup-not-stable");
            if (!r.audit().resourceComplete() || r.resourceSamples() < 30) reasons.add("resource-evidence-incomplete");
            if (r.measurementTimeMs() < 30_000) reasons.add("measurement-shorter-than-30s");
            if (r.audit().effectiveIndexState().isEmpty() || hasError(r.audit().effectiveIndexState())) reasons.add("effective-index-state-unverified");
            if (!r.stabilityDiagnostics().verified()) reasons.add("stability-unverified");
            if (r.audit().searchFailures() / (double) Math.max(1, r.queryExecutions()) > errorRate) reasons.add("search-error-rate-exceeded");
            if (r.audit().invalidResponseQueries() > 0) reasons.add("adapter-contract-violation");
            if (r.filtered().emptyGroundTruthViolations() + r.unfiltered().emptyGroundTruthViolations() > 0) reasons.add("no-hit-filter-violation");
            if (r.filtered().scoredQueries() + r.unfiltered().scoredQueries() == 0) reasons.add("no-scored-queries");
            for (QuerySegment slice : List.of(r.filtered(), r.unfiltered())) {
                if (slice.recall() != null && slice.recall() < recall) reasons.add("recall-below-threshold");
                if (slice.queryExecutions() > 0 && slice.p95Ms() > p95) reasons.add("slice-p95-above-threshold");
            }
            if (r.p95LatencyMs() > p95) reasons.add("p95-above-threshold");
            if (r.peakMemoryBytes() < 0 || r.peakMemoryBytes() > ram) reasons.add("ram-missing-or-above-threshold");
        }
        return new Verdict(reasons.isEmpty(), builds, List.copyOf(reasons));
    }
    private static boolean hasError(Map<String, Object> state) {
        return state.keySet().stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).contains("error"));
    }
    private static double number(Map<?, ?> values, String key, double fallback) {
        return values.get(key) instanceof Number n ? n.doubleValue() : fallback;
    }
    public record Verdict(boolean eligible, long independentRebuilds, List<String> reasons) { }
}
