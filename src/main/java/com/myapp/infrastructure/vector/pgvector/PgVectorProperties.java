package com.myapp.infrastructure.vector.pgvector;

import com.myapp.domain.vector.DistanceMetric;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vector.pgvector")
public class PgVectorProperties {
    private String table = "vector_documents";
    private int dimension = 1024;
    private DistanceMetric metric = DistanceMetric.COSINE;
    private String indexType = "hnsw";
    private int hnswM = 16;
    private int efConstruction = 128;
    private int ivfLists = 10;
    private int defaultEfSearch = 100;
    private boolean forceIndexScan = true;

    public String getTable() {
        if (!table.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalStateException("Invalid pgvector table name");
        return table;
    }
    public void setTable(String table) { this.table = table; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public DistanceMetric getMetric() { return metric; }
    public void setMetric(DistanceMetric metric) { this.metric = metric; }
    public String getIndexType() {
        String normalized = indexType == null ? "" : indexType.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("hnsw") && !normalized.equals("ivfflat")) {
            throw new IllegalStateException("vector.pgvector.index-type must be hnsw or ivfflat");
        }
        return normalized;
    }
    public void setIndexType(String indexType) { this.indexType = indexType; }
    public int getHnswM() { return hnswM; }
    public void setHnswM(int hnswM) { this.hnswM = hnswM; }
    public int getEfConstruction() { return efConstruction; }
    public void setEfConstruction(int efConstruction) { this.efConstruction = efConstruction; }
    public int getIvfLists() { return ivfLists; }
    public void setIvfLists(int ivfLists) { this.ivfLists = ivfLists; }
    public int getDefaultEfSearch() { return defaultEfSearch; }
    public void setDefaultEfSearch(int defaultEfSearch) { this.defaultEfSearch = defaultEfSearch; }
    public boolean isForceIndexScan() { return forceIndexScan; }
    public void setForceIndexScan(boolean forceIndexScan) { this.forceIndexScan = forceIndexScan; }
}
