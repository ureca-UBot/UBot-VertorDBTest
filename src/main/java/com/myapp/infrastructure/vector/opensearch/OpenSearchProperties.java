package com.myapp.infrastructure.vector.opensearch;

import com.myapp.domain.vector.DistanceMetric;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vector.opensearch")
public class OpenSearchProperties {
    private String baseUrl = "http://localhost:9200";
    private String username = "";
    private String password = "";
    private String index = "benchmark-chunks";
    private int dimension = 1024;
    private DistanceMetric metric = DistanceMetric.COSINE;
    private String engine = "lucene";
    private String indexType = "hnsw";
    private int hnswM = 16;
    private int efConstruction = 128;
    private int defaultEfSearch = 100;
    private int ivfNlist = 128;
    private String ivfModelId = "benchmark-faiss-ivf";
    private String trainingIndex = "benchmark-faiss-training";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getIndex() { return index; }
    public void setIndex(String index) { this.index = index; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public DistanceMetric getMetric() { return metric; }
    public void setMetric(DistanceMetric metric) { this.metric = metric; }
    public String getEngine() {
        String normalized = engine == null ? "" : engine.toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("lucene", "faiss", "jvector").contains(normalized)) {
            throw new IllegalStateException("Unsupported OpenSearch engine: " + engine);
        }
        return normalized;
    }
    public void setEngine(String engine) { this.engine = engine; }
    public String getIndexType() {
        String normalized = indexType == null ? "" : indexType.toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("hnsw", "ivf", "disk_ann").contains(normalized)) {
            throw new IllegalStateException("Unsupported OpenSearch index type: " + indexType);
        }
        return normalized;
    }
    public void setIndexType(String indexType) { this.indexType = indexType; }
    public int getHnswM() { return hnswM; }
    public void setHnswM(int hnswM) { this.hnswM = hnswM; }
    public int getEfConstruction() { return efConstruction; }
    public void setEfConstruction(int efConstruction) { this.efConstruction = efConstruction; }
    public int getDefaultEfSearch() { return defaultEfSearch; }
    public void setDefaultEfSearch(int defaultEfSearch) { this.defaultEfSearch = defaultEfSearch; }
    public int getIvfNlist() { return ivfNlist; }
    public void setIvfNlist(int ivfNlist) { this.ivfNlist = ivfNlist; }
    public String getIvfModelId() { return ivfModelId; }
    public void setIvfModelId(String ivfModelId) { this.ivfModelId = ivfModelId; }
    public String getTrainingIndex() { return trainingIndex; }
    public void setTrainingIndex(String trainingIndex) { this.trainingIndex = trainingIndex; }
}
