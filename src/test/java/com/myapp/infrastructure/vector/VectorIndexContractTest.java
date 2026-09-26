package com.myapp.infrastructure.vector;

import com.myapp.infrastructure.vector.milvus.MilvusIndexManager;
import com.myapp.infrastructure.vector.milvus.MilvusProperties;
import com.myapp.infrastructure.vector.opensearch.OpenSearchIndexManager;
import com.myapp.infrastructure.vector.opensearch.OpenSearchProperties;
import com.myapp.infrastructure.vector.pgvector.PgVectorIndexManager;
import com.myapp.infrastructure.vector.pgvector.PgVectorProperties;
import com.myapp.infrastructure.vector.qdrant.QdrantIndexManager;
import com.myapp.infrastructure.vector.qdrant.QdrantProperties;
import com.myapp.infrastructure.vector.weaviate.WeaviateIndexManager;
import com.myapp.infrastructure.vector.weaviate.WeaviateProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class VectorIndexContractTest {
    @Test
    void exposesPgvectorSearchControls() {
        PgVectorProperties properties = new PgVectorProperties();
        PgVectorIndexManager manager = new PgVectorIndexManager(null, properties);
        assertThat(manager.engine()).isEqualTo("PostgreSQL");
        assertThat(manager.searchParameterName()).isEqualTo("ef_search");

        properties.setIndexType("IVFFlat");
        assertThat(manager.indexType()).isEqualTo("ivfflat");
        assertThat(manager.searchParameterName()).isEqualTo("probes");
        assertThat(manager.minimumSearchParameter(10)).isOne();
        assertThat(manager.maximumSearchParameter()).isEqualTo(10);
    }

    @Test
    void exposesNativeEngineSearchControls() {
        assertThat(new QdrantIndexManager(null, new QdrantProperties()).searchParameterName()).isEqualTo("hnsw_ef");

        WeaviateProperties weaviate = new WeaviateProperties();
        WeaviateIndexManager weaviateManager = new WeaviateIndexManager(null, weaviate);
        assertThat(weaviateManager.searchParameterName()).isEqualTo("ef");
        weaviate.setIndexType("HFresh");
        assertThat(weaviateManager.searchParameterName()).isEqualTo("searchProbe");

        MilvusProperties milvus = new MilvusProperties();
        MilvusIndexManager milvusManager = new MilvusIndexManager(null, milvus);
        assertThat(milvusManager.searchParameterName()).isEqualTo("ef");
        milvus.setIndexType("IVF_PQ");
        assertThat(milvusManager.searchParameterName()).isEqualTo("nprobe");
        milvus.setIndexType("DISKANN");
        assertThat(milvusManager.searchParameterName()).isEqualTo("search_list");
    }

    @Test
    void usesCandidateCountInsteadOfIgnoredEfForLuceneAndJvector() {
        OpenSearchProperties properties = new OpenSearchProperties();
        OpenSearchIndexManager manager = manager(properties);
        assertThat(manager.engine()).isEqualTo("Lucene");
        assertThat(manager.searchParameterName()).isEqualTo("candidate_k");

        properties.setEngine("faiss");
        assertThat(manager(properties).searchParameterName()).isEqualTo("ef_search");
        properties.setIndexType("ivf");
        assertThat(manager(properties).searchParameterName()).isEqualTo("nprobes");

        properties.setEngine("jvector");
        properties.setIndexType("disk_ann");
        assertThat(manager(properties).engine()).isEqualTo("JVector");
        assertThat(manager(properties).searchParameterName()).isEqualTo("candidate_k");
    }

    private OpenSearchIndexManager manager(OpenSearchProperties properties) {
        return new OpenSearchIndexManager(null, properties, JsonMapper.builder().build());
    }
}
