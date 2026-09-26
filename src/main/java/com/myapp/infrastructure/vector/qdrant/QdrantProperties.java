package com.myapp.infrastructure.vector.qdrant;

import com.myapp.domain.vector.DistanceMetric;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "vector.qdrant")
public class QdrantProperties {
    private String baseUrl = "http://localhost:6333";
    private String apiKey = "";
    private String collection = "benchmark_chunks";
    private int dimension = 1024;
    private DistanceMetric metric = DistanceMetric.COSINE;
    private int hnswM = 16;
    private int efConstruction = 128;
    private int defaultEfSearch = 100;
    private int fullScanThreshold = 10;
    private int indexingThreshold = 10;
    // Metadata keys that queries filter on, mapped to their Qdrant field schema.
    // Without a payload index Qdrant cannot use filtered HNSW and falls back to a full scan.
    private Map<String, String> payloadIndexFields = new LinkedHashMap<>();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public DistanceMetric getMetric() { return metric; }
    public void setMetric(DistanceMetric metric) { this.metric = metric; }
    public int getHnswM() { return hnswM; }
    public void setHnswM(int hnswM) { this.hnswM = hnswM; }
    public int getEfConstruction() { return efConstruction; }
    public void setEfConstruction(int efConstruction) { this.efConstruction = efConstruction; }
    public int getDefaultEfSearch() { return defaultEfSearch; }
    public void setDefaultEfSearch(int defaultEfSearch) { this.defaultEfSearch = defaultEfSearch; }
    public int getFullScanThreshold() { return fullScanThreshold; }
    public void setFullScanThreshold(int fullScanThreshold) { this.fullScanThreshold = fullScanThreshold; }
    public int getIndexingThreshold() { return indexingThreshold; }
    public void setIndexingThreshold(int indexingThreshold) { this.indexingThreshold = indexingThreshold; }
    public Map<String, String> getPayloadIndexFields() { return Map.copyOf(payloadIndexFields); }
    public void setPayloadIndexFields(Map<String, String> payloadIndexFields) {
        this.payloadIndexFields = payloadIndexFields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payloadIndexFields);
    }
}
