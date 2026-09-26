package com.myapp.benchmark;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkScenarioTest {
    @Test
    void apiRequestWithoutOptionalRunFieldsDefaultsToFiveRepetitions() {
        var scenario = JsonMapper.builder().build().readValue("""
                {"topK":10,"concurrency":10,"warmupIterations":1,"measurementIterations":5,
                 "searchParameterValues":[16,32,64,96,128]}
                """, BenchmarkScenario.class);
        assertThat(scenario.runNumber()).isEqualTo(1);
        assertThat(scenario.repetitions()).isEqualTo(5);
        assertThat(scenario.searchParameterValues()).containsExactly(16, 32, 64, 96, 128);
        assertThat(scenario.searchParameters()).isEmpty();
    }
}
