package com.myapp.benchmark;

import com.myapp.domain.vector.DistanceMetric;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "benchmark")
public class BenchmarkProperties {
    private Path documentVectors = Path.of("data/embeddings/document-vectors.jsonl");
    private Path queryDefinitions = Path.of("data/queries/queries.jsonl");
    private Path queryVectors = Path.of("data/embeddings/query-vectors.jsonl");
    private Path resultDirectory = Path.of("benchmark-result/fairness-v2");
    private DistanceMetric metric = DistanceMetric.COSINE;
    private List<String> containerNames = new ArrayList<>();
    private int upsertBatchSize = 256;
    private List<Integer> searchParameterValues = new ArrayList<>(List.of(10, 20, 40, 80, 120, 200, 400, 800, 1000));
    private int calibrationQueryCount = 100;
    private long minimumMeasurementTimeMs = 30_000;
    private long maximumMeasurementTimeMs = 180_000;
    private int minimumResourceSamples = 30;
    private int warmupMaximumPasses = 10;
    private double warmupRelativeTolerance = .15;
    private double tieTolerance = 0;
    private String mode = "exploratory";
    private Path validationPlan;
    private int driftDiagnosticRepetitions = 3;
    private double driftThreshold = 0.05;
    private double resourceBudgetCpu = 4.0;
    private long resourceBudgetMemoryBytes = 8L * 1024 * 1024 * 1024;

    public Path getDocumentVectors() { return documentVectors; }
    public void setDocumentVectors(Path documentVectors) { this.documentVectors = documentVectors; }
    public Path getQueryDefinitions() { return queryDefinitions; }
    public void setQueryDefinitions(Path queryDefinitions) { this.queryDefinitions = queryDefinitions; }
    public Path getQueryVectors() { return queryVectors; }
    public void setQueryVectors(Path queryVectors) { this.queryVectors = queryVectors; }
    public Path getResultDirectory() { return resultDirectory; }
    public void setResultDirectory(Path resultDirectory) { this.resultDirectory = resultDirectory; }
    public DistanceMetric getMetric() { return metric; }
    public void setMetric(DistanceMetric metric) { this.metric = metric; }
    public List<String> getContainerNames() { return List.copyOf(containerNames); }
    public void setContainerNames(List<String> containerNames) {
        this.containerNames = containerNames == null ? new ArrayList<>() : new ArrayList<>(containerNames);
    }
    public int getUpsertBatchSize() { return upsertBatchSize; }
    public void setUpsertBatchSize(int upsertBatchSize) { this.upsertBatchSize = upsertBatchSize; }
    public List<Integer> getSearchParameterValues() { return searchParameterValues; }
    public void setSearchParameterValues(List<Integer> values) { this.searchParameterValues = values; }
    public int getCalibrationQueryCount() { return calibrationQueryCount; }
    public void setCalibrationQueryCount(int calibrationQueryCount) { this.calibrationQueryCount = calibrationQueryCount; }
    public long getMinimumMeasurementTimeMs() { return minimumMeasurementTimeMs; }
    public void setMinimumMeasurementTimeMs(long value) { minimumMeasurementTimeMs = value; }
    public long getMaximumMeasurementTimeMs() { return maximumMeasurementTimeMs; }
    public void setMaximumMeasurementTimeMs(long value) { maximumMeasurementTimeMs = value; }
    public int getMinimumResourceSamples() { return minimumResourceSamples; }
    public void setMinimumResourceSamples(int value) { minimumResourceSamples = value; }
    public int getWarmupMaximumPasses() { return warmupMaximumPasses; }
    public void setWarmupMaximumPasses(int value) { warmupMaximumPasses = value; }
    public double getWarmupRelativeTolerance() { return warmupRelativeTolerance; }
    public void setWarmupRelativeTolerance(double value) { warmupRelativeTolerance = value; }
    public double getTieTolerance() { return tieTolerance; }
    public void setTieTolerance(double value) { tieTolerance = value; }
    public String getMode() { return mode; }
    public void setMode(String value) { mode = value; }
    public Path getValidationPlan() { return validationPlan; }
    public void setValidationPlan(Path value) { validationPlan = value; }
    public int getDriftDiagnosticRepetitions() { return driftDiagnosticRepetitions; }
    public void setDriftDiagnosticRepetitions(int driftDiagnosticRepetitions) {
        this.driftDiagnosticRepetitions = driftDiagnosticRepetitions;
    }
    public double getDriftThreshold() { return driftThreshold; }
    public void setDriftThreshold(double driftThreshold) { this.driftThreshold = driftThreshold; }
    public double getResourceBudgetCpu() { return resourceBudgetCpu; }
    public void setResourceBudgetCpu(double resourceBudgetCpu) { this.resourceBudgetCpu = resourceBudgetCpu; }
    public long getResourceBudgetMemoryBytes() { return resourceBudgetMemoryBytes; }
    public void setResourceBudgetMemoryBytes(long resourceBudgetMemoryBytes) {
        this.resourceBudgetMemoryBytes = resourceBudgetMemoryBytes;
    }
}
