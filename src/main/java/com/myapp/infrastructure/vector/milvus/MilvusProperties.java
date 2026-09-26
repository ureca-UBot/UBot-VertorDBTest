package com.myapp.infrastructure.vector.milvus;

import com.myapp.domain.vector.DistanceMetric;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vector.milvus")
public class MilvusProperties {
    private String baseUrl = "http://localhost:19530";
    private String token = "root:Milvus";
    private String database = "default";
    private String collection = "benchmark_chunks";
    private int dimension = 1024;
    private DistanceMetric metric = DistanceMetric.COSINE;
    private String indexType = "HNSW";
    private int hnswM = 16;
    private int efConstruction = 128;
    private int defaultEfSearch = 100;
    private int ivfNlist = 128;
    private int pqM = 64;
    private int pqNbits = 8;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public DistanceMetric getMetric() { return metric; }
    public void setMetric(DistanceMetric metric) { this.metric = metric; }
    public String getIndexType() {
        String normalized = indexType == null ? "" : indexType.toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("HNSW", "IVF_FLAT", "IVF_SQ8", "IVF_PQ", "DISKANN").contains(normalized)) {
            throw new IllegalStateException("Unsupported Milvus index type: " + indexType);
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
    public int getPqM() { return pqM; }
    public void setPqM(int pqM) { this.pqM = pqM; }
    public int getPqNbits() { return pqNbits; }
    public void setPqNbits(int pqNbits) { this.pqNbits = pqNbits; }
}
