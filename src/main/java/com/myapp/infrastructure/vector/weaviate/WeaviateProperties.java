package com.myapp.infrastructure.vector.weaviate;

import com.myapp.domain.vector.DistanceMetric;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "vector.weaviate")
public class WeaviateProperties {
    private String baseUrl = "http://localhost:18080";
    private String apiKey = "";
    private String className = "BenchmarkChunk";
    private int dimension = 1024;
    private DistanceMetric metric = DistanceMetric.COSINE;
    private String indexType = "hnsw";
    private int hnswM = 16;
    private int efConstruction = 128;
    private int ef = 100;
    private int hfreshMaxPostingSizeKb = 48;
    private int hfreshReplicas = 4;
    private int hfreshSearchProbe = 256;
    private Map<String, String> filterFields = new LinkedHashMap<>();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public DistanceMetric getMetric() { return metric; }
    public void setMetric(DistanceMetric metric) { this.metric = metric; }
    public String getIndexType() {
        String normalized = indexType == null ? "" : indexType.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("hnsw") && !normalized.equals("hfresh")) {
            throw new IllegalStateException("vector.weaviate.index-type must be hnsw or hfresh");
        }
        return normalized;
    }
    public void setIndexType(String indexType) { this.indexType = indexType; }
    public int getHnswM() { return hnswM; }
    public void setHnswM(int hnswM) { this.hnswM = hnswM; }
    public int getEfConstruction() { return efConstruction; }
    public void setEfConstruction(int efConstruction) { this.efConstruction = efConstruction; }
    public int getEf() { return ef; }
    public void setEf(int ef) { this.ef = ef; }
    public int getHfreshMaxPostingSizeKb() { return hfreshMaxPostingSizeKb; }
    public void setHfreshMaxPostingSizeKb(int value) { this.hfreshMaxPostingSizeKb = value; }
    public int getHfreshReplicas() { return hfreshReplicas; }
    public void setHfreshReplicas(int value) { this.hfreshReplicas = value; }
    public int getHfreshSearchProbe() { return hfreshSearchProbe; }
    public void setHfreshSearchProbe(int value) { this.hfreshSearchProbe = value; }
    public Map<String, String> getFilterFields() { return filterFields; }
    public void setFilterFields(Map<String, String> filterFields) { this.filterFields = filterFields; }
}
