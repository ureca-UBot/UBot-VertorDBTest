package com.myapp.benchmark;

import java.util.List;
import java.util.Map;

public record StabilityDiagnostics(
        boolean required,
        boolean verified,
        double driftThreshold,
        Double calibrationRecall,
        List<Double> serialRecallSamples,
        List<Double> concurrentRecallSamples,
        Map<String, Object> stateBefore,
        Map<String, Object> stateAfter,
        String reason
) {
    public StabilityDiagnostics {
        serialRecallSamples = serialRecallSamples == null ? List.of() : List.copyOf(serialRecallSamples);
        concurrentRecallSamples = concurrentRecallSamples == null ? List.of() : List.copyOf(concurrentRecallSamples);
        stateBefore = stateBefore == null ? Map.of() : Map.copyOf(stateBefore);
        stateAfter = stateAfter == null ? Map.of() : Map.copyOf(stateAfter);
        reason = reason == null ? "" : reason;
    }

    public static StabilityDiagnostics notRequired() {
        return new StabilityDiagnostics(false, true, 0, null, List.of(), List.of(), Map.of(), Map.of(),
                "Adapter has no mandatory drift audit");
    }
}
