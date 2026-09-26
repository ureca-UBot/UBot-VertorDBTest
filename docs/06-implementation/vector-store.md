# VectorStore Port

DB 중립 계약입니다. 애플리케이션과 벤치마크 코드는 이 인터페이스만 봅니다.

## VectorStore

```java
public interface VectorStore {
    String database();
    int dimension();
    DistanceMetric metric();
    void upsert(List<VectorDocument> documents);
    List<VectorSearchResult> search(VectorSearchRequest request);
    void deleteAll();
    long count();
}
```

| 메서드 | 계약 |
|---|---|
| `database()` | 결과 파일에 기록되는 소문자 식별자; 검색 파라미터 키는 IndexManager가 제공 |
| `dimension()` / `metric()` | 실행 전 입력과 대조. 불일치 시 벤치마크가 거부 |
| `upsert()` | 같은 `id`면 덮어씀. 적재 요청을 완료함. 검색 준비 완료는 `awaitReady()`와 건수 검증으로 확인 |
| `search()` | **측정 타이머가 감싸는 유일한 메서드.** 높을수록 좋은 score로 정규화해 반환 |
| `count()` | 정확한 건수. 적재 검증에 사용 |

### score 정규화

DB마다 거리/유사도 표현이 다르므로 어댑터가 통일합니다.

```java
case COSINE -> cosine(...)          // 그대로
case DOT -> dot(...)                // 그대로
case EUCLIDEAN -> -euclidean(...)   // 부호 반전
```

Weaviate cosine은 `1 - distance`, Milvus·Qdrant는 EUCLIDEAN일 때 반환 거리의 부호를 반전합니다. 이 score 정규화와 별개로 Recall의 정답·경계 동점은 원본 벡터 exact 검색에서 계산합니다.

## VectorIndexManager

```java
public interface VectorIndexManager {
    String indexType();
    default String engine() { return "Native"; }
    default String searchParameterName() { return null; }
    default int minimumSearchParameter(int topK) { return topK; }
    default int maximumSearchParameter() { return Integer.MAX_VALUE; }
    void create();
    void drop();

    default void rebuild() { drop(); create(); }
    default void rebuild(List<VectorDocument> trainingDocuments) { rebuild(); }
    default long indexSizeBytes() { return -1L; }
    default Map<String, Object> indexParameters() { return Map.of(); }
    default void configureSearch(Map<String, Object> searchParameters) { }
    default void awaitReady(long expectedVectorCount, Duration timeout) { }
    default boolean requiresStabilityCheck() { return false; }
    default Map<String, Object> diagnostics() { return Map.of(); }
}
```

default 구현이 있는 메서드는 **필요한 제품만 재정의합니다.**

| 메서드 | 재정의하는 제품 | 이유 |
|---|---|---|
| `rebuild(documents)` | OpenSearch Faiss IVF | model training이 먼저 필요 |
| `configureSearch()` | Weaviate | `ef`/`searchProbe`가 요청별이 아니라 클래스 설정 |
| `awaitReady()` | 다섯 DB 모두 | 적재 건수·인덱스 생성·비동기 준비 상태 확인 |
| `minimumSearchParameter()` / `maximumSearchParameter()` | IVF 계열 등 | 전체 그리드를 측정 전에 허용 범위와 대조 |
| `diagnostics()` | 다섯 DB 모두 | 실제 생성 설정/상태 readback; Milvus는 index/load/query-segment 상태 포함 |
| `indexSizeBytes()` | pgvector, OpenSearch | 나머지는 미지원(`-1`) |

`drop()`은 대상이 없어도 성공해야 합니다. 첫 실행에서 `rebuild()`가 실패하면 안 되기 때문입니다.

```java
try {
    client.delete("/collections/" + properties.getCollection());
} catch (VectorStoreHttpException exception) {
    if (exception.statusCode() != 404) throw exception;
}
```

## 도메인 모델

### VectorDocument

```java
record VectorDocument(String id, String documentId, String chunkId,
                      String content, float[] embedding, Map<String, Object> metadata)
```

### VectorSearchRequest

```java
record VectorSearchRequest(float[] queryVector, int topK,
                           VectorFilter filter, Map<String, Object> searchParameters)
```

- `queryVector`는 생성자와 accessor 양쪽에서 방어적 복사합니다.
- `intParameter(name, fallback)`으로 어댑터가 자기 파라미터를 읽습니다.
- 하네스는 실행 전 고정 파라미터 키와 정수값, 해당 인덱스의 허용 범위를 검증합니다.

```java
int efSearch = request.intParameter("ef_search", properties.getDefaultEfSearch());
```

### VectorFilter

```java
record VectorFilter(Map<String, Object> equals)
```

현재 지원하는 것은 **equality 조합(AND)뿐**입니다.
range, IN, OR은 지원하지 않습니다. 확장하면 `ExactSearchEngine.matches()`와
다섯 어댑터를 모두 같이 고쳐야 합니다.

문자열·boolean·숫자는 JSON 타입을 보존합니다. 숫자 2와 2.0은 동등하지만 문자열 "2"와 숫자 2는 다릅니다. `MetadataContract`가 필터 키별 혼합 타입을 사전 거부합니다.

### VectorSearchResult

```java
record VectorSearchResult(String id, String documentId, String chunkId, double score)
```

`id`가 Recall 계산의 기준입니다. 어댑터는 DB가 생성한 내부 id가 아니라
**원본 `id`를 반환해야 합니다.**

`ResponseContract`는 본 측정 타이머가 종료된 후 ID/문서/청크/필터/score/개수 계약을 확인합니다. 어댑터가 잘못된 응답을 고쳐 숨기면 안 됩니다. 최신 측정과 남은 진단 한계는 [620개 결과 보고서](../07-results/fairness-v2-results-20260913.md)를 참고합니다.

## 관련 문서

- [adapter-policy.md](adapter-policy.md)
- [code-architecture.md](code-architecture.md)
