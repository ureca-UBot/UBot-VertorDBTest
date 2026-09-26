package com.myapp.api;

import com.myapp.benchmark.BenchmarkRunner;
import com.myapp.benchmark.BenchmarkScenario;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/benchmarks")
public class BenchmarkController {
    private final BenchmarkRunner benchmarkRunner;

    public BenchmarkController(BenchmarkRunner benchmarkRunner) {
        this.benchmarkRunner = benchmarkRunner;
    }

    @PostMapping("/run")
    public ResponseEntity<BenchmarkRunner.RunOutput> run(@RequestBody RunRequest request) {
        return ResponseEntity.ok(benchmarkRunner.run(request.scenarios(), request.rebuildAndLoad()));
    }

    public record RunRequest(List<BenchmarkScenario> scenarios, boolean rebuildAndLoad) {
    }
}
