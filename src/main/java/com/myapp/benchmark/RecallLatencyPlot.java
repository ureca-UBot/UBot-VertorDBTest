package com.myapp.benchmark;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Every circle is an actual measurement; reference levels never filter, fit or move the data. */
public final class RecallLatencyPlot {
    private RecallLatencyPlot() { }

    public static String svg(List<BenchmarkResult> results, ObjectMapper mapper, String provenance) {
        if (results.isEmpty()) throw new IllegalArgumentException("No measurements to plot");
        double max = Math.max(1, results.stream().mapToDouble(BenchmarkResult::p95LatencyMs).max().orElse(1) * 1.08);
        String[] palette = {"#2563eb", "#dc2626", "#059669", "#7c3aed", "#ea580c", "#0891b2", "#be185d",
                "#4d7c0f", "#4f46e5", "#b45309", "#0f766e", "#a21caf", "#475569", "#9f1239"};
        Map<String, String> colors = new LinkedHashMap<>();
        results.stream().map(RecallLatencyPlot::label).distinct().sorted()
                .forEach(label -> colors.put(label, palette[colors.size() % palette.length]));
        int height = Math.max(820, 175 + colors.size() * 34);
        StringBuilder svg = new StringBuilder("""
                <svg xmlns="http://www.w3.org/2000/svg" width="1400" height="%d" viewBox="0 0 1400 %d" role="img" aria-labelledby="title description">
                <title id="title">Recall versus p95 latency — all measurements</title>
                <desc id="description">X is overall p95 latency in milliseconds. Y is overall Recall. Dashed lines at 0.90 and 0.95 are quality references only. Hover over a point for its configuration and metrics.</desc>
                <style>text{font-family:Arial,sans-serif;fill:#334155} .point:hover{r:8;fill-opacity:1;stroke:#0f172a;stroke-width:2}</style>
                <rect width="100%%" height="100%%" fill="#f8fafc"/>
                <text x="100" y="43" font-size="25" font-weight="bold">Recall–Latency trade-off</text>
                <text x="100" y="73" font-size="14">%d measured points · all repetitions retained · hover for configuration and resources</text>
                <text x="100" y="100" font-size="12">%s</text>
                <rect x="100" y="140" width="860" height="540" fill="white"/>
                """.formatted(height, height, results.size(), xml(provenance)));
        for (int tick = 0; tick <= 5; tick++) {
            double x = 100 + tick * 172;
            double y = 680 - tick * 108;
            svg.append(String.format(Locale.ROOT, """
                    <line x1="%.2f" y1="140" x2="%.2f" y2="680" stroke="#e2e8f0"/>
                    <text x="%.2f" y="707" text-anchor="middle" font-size="12">%.2f</text>
                    <line x1="100" y1="%.2f" x2="960" y2="%.2f" stroke="#e2e8f0"/>
                    <text x="84" y="%.2f" text-anchor="end" font-size="12">%.1f</text>
                    """, x, x, x, max * tick / 5, y, y, y + 4, tick / 5.0));
        }
        for (double reference : List.of(.90, .95)) {
            double y = 680 - reference * 540;
            svg.append(String.format(Locale.ROOT, """
                    <line class="quality-reference" data-recall="%.2f" x1="100" y1="%.2f" x2="960" y2="%.2f" stroke="#64748b" stroke-dasharray="7 5"/>
                    <text x="952" y="%.2f" text-anchor="end" font-size="12">Recall %.2f · reference</text>
                    """, reference, y, y, y - 6, reference));
        }
        for (BenchmarkResult result : results) {
            String description = label(result) + " | " + result.testId() + " / repetition " + result.runNumber()
                    + " | search=" + mapper.writeValueAsString(result.searchParameters())
                    + " | build=" + mapper.writeValueAsString(result.indexParameters())
                    + String.format(Locale.ROOT, " | Recall@%d=%.6f | p50=%.3f ms | p95=%.3f ms | p99=%.3f ms | QPS=%.3f",
                    result.topK(), result.actualRecall(), result.p50LatencyMs(), result.p95LatencyMs(), result.p99LatencyMs(), result.qps())
                    + " | CPU avg/peak=" + result.averageCpuPercent() + "/" + result.peakCpuPercent()
                    + " | RAM avg/peak bytes=" + result.averageMemoryBytes() + "/" + result.peakMemoryBytes()
                    + " | duration ms=" + result.measurementTimeMs() + " | resource samples=" + result.resourceSamples()
                    + " | vectors=" + result.vectorCount() + " | concurrency=" + result.concurrency()
                    + " | measuredAt=" + result.measuredAt();
            svg.append(String.format(Locale.ROOT,
                    "<circle class=\"point\" data-recall=\"%.8f\" data-p95-ms=\"%.8f\" cx=\"%.2f\" cy=\"%.2f\" r=\"5\" fill=\"%s\" fill-opacity=\"0.72\" stroke=\"white\" stroke-width=\"0.8\"><title>%s</title></circle>%n",
                    result.actualRecall(), result.p95LatencyMs(), 100 + result.p95LatencyMs() / max * 860,
                    680 - result.actualRecall() * 540, colors.get(label(result)), xml(description)));
        }
        int y = 159;
        for (var entry : colors.entrySet()) {
            svg.append("<circle cx=\"1002\" cy=\"").append(y - 4).append("\" r=\"5\" fill=\"")
                    .append(entry.getValue()).append("\"/><text x=\"1017\" y=\"").append(y)
                    .append("\" font-size=\"13\">").append(xml(entry.getKey())).append("</text>\n");
            y += 34;
        }
        String recallLabel = results.stream().map(BenchmarkResult::topK).distinct().count() == 1
                ? "Recall@" + results.getFirst().topK() : "Recall@K (K in tooltip)";
        svg.append("""
                <path d="M100 140V680H960" fill="none" stroke="#334155"/>
                <text x="530" y="748" text-anchor="middle" font-size="17">p95 latency (ms)</text>
                <text x="35" y="410" transform="rotate(-90 35 410)" text-anchor="middle" font-size="17">%s</text>
                <text x="100" y="790" font-size="12">Overall workload on both axes. Reference levels are not PASS/FAIL conditions. Coincident points may overlap.</text>
                </svg>
                """.formatted(recallLabel));
        return svg.toString();
    }

    private static String label(BenchmarkResult result) {
        return result.database() + " / " + result.engine() + " / " + result.indexType();
    }

    private static String xml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
