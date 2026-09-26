package com.myapp.benchmark;

import java.util.List;
import java.util.Map;

public record MeasurementAudit(String buildId, boolean rebuilt, Map<String, Object> effectiveIndexState,
                               List<Double> warmupP95Ms, boolean warmupStable, boolean resourceComplete,
                               int searchFailures, int invalidResponseQueries, String queryAuditFile) {
    public static MeasurementAudit unavailable() {
        return new MeasurementAudit("unavailable", false, Map.of(), List.of(), false, false, 0, 0, "");
    }
}
