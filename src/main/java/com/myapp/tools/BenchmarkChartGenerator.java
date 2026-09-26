package com.myapp.tools;

import com.myapp.benchmark.BenchmarkResult;
import com.myapp.benchmark.RecallLatencyPlot;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Replots saved measurements without rerunning searches or altering their original protocol/data. */
public final class BenchmarkChartGenerator {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <result-directory> <new-output.svg>");
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Choose a new chart output path; refusing to overwrite " + output);
        var mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        List<BenchmarkResult> results = new ArrayList<>();
        try (var files = Files.list(input.resolve("raw"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("benchmark-"))
                    .filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                results.addAll(List.of(mapper.readValue(file.toFile(), BenchmarkResult[].class)));
            }
        }
        String provenance = Files.exists(input.resolve("protocol.txt"))
                ? Files.readString(input.resolve("protocol.txt")).trim()
                : "Historical selected-parameter measurements; this is not a complete parameter sweep";
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, RecallLatencyPlot.svg(results, mapper, provenance), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("Plotted " + results.size() + " original measurements: " + output.toAbsolutePath());
    }
}
