# Adapter Policy

어댑터를 추가하거나 고칠 때 지켜야 하는 규칙입니다.
공정한 비교를 위한 제약이므로 편의를 이유로 어기면 결과가 무효가 됩니다.

## 1. 검색 경로에 제품별 SDK를 쓰지 않습니다

pgvector를 제외한 네 DB의 적재·검색 경로는 모두 Java 표준 `HttpClient`를 공유합니다.

```java
// build.gradle
// External vector DB adapters intentionally share Java's HTTP client so that
// client-library overhead is controlled. pgvector uses JDBC by necessity.
```

제품별 공식 SDK는 커넥션 풀, 재시도, 직렬화 최적화 수준이 다릅니다. 이 하네스는 HTTP 클라이언트를 공유하되 JDBC·JSON·GraphQL의 프로토콜 차이는 남습니다. 따라서 결과는 현재 어댑터를 포함한 검색 경로의 비교입니다.

Milvus 공식 Java SDK는 검색 timer 밖의 `getQuerySegmentInfo` 진단에만 사용합니다.
pgvector만 검색 경로에서 JDBC를 쓰는 비대칭은
[../03-benchmark-design/limitations.md](../03-benchmark-design/limitations.md)에 명시합니다.

## 2. 측정 타이머 안에서 추가 작업을 하지 않습니다

`search()` 안에서 하면 안 되는 것:

- 스키마 조회나 갱신
- 인덱스 상태 확인
- 재시도 루프
- 로깅, 메트릭 수집
- 결과 후처리(재정렬, 중복 제거, 추가 필터)

DB가 잘못된 결과를 주면 어댑터에서 조용히 정렬·중복 제거·재필터링하지 않습니다. 타이머 종료 후 `ResponseContract`가 unknown/중복 ID, document/chunk 불일치, 필터 위반, 비유한 score, topK 초과를 검사하고 위반 응답은 Recall 0과 감사 기록으로 남깁니다. 이것은 단순히 유효하지만 낮은 Recall인 결과와 구분합니다.

현재 pgvector는 매 요청의 `set_config`와 검색을 별도 SQL로 실행하며 그 비용이 타이머에 포함됩니다. 현재 구현의 명시적 예외이지 제품 비교에서 무시해도 된다는 뜻은 아닙니다. Weaviate의 클래스 설정 변경은 타이머 밖이므로 이 비대칭을 결과 한계에 남깁니다.

## 3. 원본 id를 반환합니다

이 하네스는 Qdrant와 Weaviate의 내부 id를 UUID로 변환합니다(Qdrant는 정수 id도 지원). 변환은 결정론적이어야 하고
원본 id는 payload/property에 보관해 검색 결과에서 그대로 꺼냅니다.

```java
public static String uuid(String id) {
    try {
        return UUID.fromString(id).toString();
    } catch (IllegalArgumentException ignored) {
        return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
```

변환된 id를 반환하면 Recall이 0이 됩니다.

## 4. score를 정규화합니다

높을수록 좋은 값으로 통일합니다. EUCLIDEAN은 부호를 반전합니다.

## 5. 차원을 검사합니다

```java
if (request.queryVector().length != properties.getDimension()) {
    throw new IllegalArgumentException("Unexpected query vector dimension");
}
VectorHttpSupport.validateDimensions(documents, properties.getDimension());
```

차원이 틀리면 DB가 조용히 이상한 결과를 주거나 늦게 실패합니다. 먼저 거부합니다.

## 6. 검색 파라미터를 무시하지 않습니다

이 실험은 파라미터 변화에 따른 Recall·성능 변화를 측정합니다.
어댑터가 파라미터를 무시하면 서로 다른 설정을 측정한 것으로 잘못 기록하게 됩니다.

파라미터를 요청별로 보낼 수 없는 제품은 `configureSearch()`를 재정의해 반영합니다(Weaviate).

**`ef`를 올렸는데 latency가 전혀 변하지 않으면 파라미터가 적용되지 않은 것을 의심합니다.**

## 7. 인덱스가 준비된 뒤 측정합니다

비동기 인덱싱을 하는 제품은 `awaitReady()`를 재정의합니다.
"적재가 끝났다"와 "인덱스로 검색할 수 있다"는 다릅니다.

준비되지 않은 상태로 측정하면 exact 또는 전환 경로 수치를 ANN 수치로 기록하게 됩니다.
Milvus는 index/load REST 상태뿐 아니라 query node의 Sealed/Flushed segment와 row 합계까지 확인합니다. 다만 현재 사후 Recall 진단과 오류 키 중심의 상태 검사로는 측정 중 상태 변동을 모두 검증하지 못합니다. 최신 DISKANN 5개 경고 사례를 `verified=true`만으로 정상이라고 단정하지 않습니다.

## 8. 선언한 실행 조건의 누락을 측정 전에 검사합니다

준비 상태, 필수 인덱스, 입력 차원·건수, 자원 상한이 선언과 다르면 실행 오류로 처리합니다. 이는 낮은 Recall이나 느린 latency를 탈락시키는 규칙이 아닙니다. 조건이 충족된 뒤 얻은 모든 성능·품질 관측은 보존합니다.

```java
if (!missing.isEmpty()) {
    throw new IllegalStateException("Qdrant payload index is missing for " + missing
            + "; filtered search would fall back to a full scan");
}
```

이 규칙은 1차 실험이 무효가 된 뒤에 추가됐습니다.
[../07-results/analysis.md](../07-results/analysis.md)를 봅니다.

## 9. 필터 필드를 선언형으로 관리합니다

필터 대상 필드는 코드에 하드코딩하지 않고 프로필 YAML에 둡니다.

```yaml
vector.weaviate.filter-fields: {tenant_id: text, status: text, ...}
vector.qdrant.payload-index-fields: {tenant_id: keyword, status: keyword, ...}
```

이미 지원하는 scalar 타입의 필터 키 추가는 YAML에서 선언합니다. 새로운 타입·연산은 `MetadataContract`, `VectorFilter`, 정확 검색 및 모든 어댑터를 함께 검토합니다. JSON 숫자 표현 2/2.0은 같게 비교하되 문자열/boolean과 강제 변환하지 않고 키별 혼합 타입은 사전 거부합니다. Weaviate text equality는 `tokenization: field`, pgvector는 JSONB equality를 사용합니다.

## 10. 관측과 선정 기준을 분리합니다

입력·응답·자원 수집의 유효성 검사와 서비스 성능 판정은 다릅니다. 어댑터는 측정값을 보존할 뿐 선정하지 않습니다. 원시 경고점을 지우지 않습니다. 선정 결과(pgvector)는 사람이 다축 산포도와 5회 반복 변동을 보고 내렸으며 [선정 결론](../07-results/decision.md)에 있습니다. 코드의 `DecisionGate` 기본 2GiB·30ms는 합의된 기준이 아니어서 선정에 쓰지 않았고, 코드는 남아 있습니다.

## 새 어댑터 추가 체크리스트

- [ ] `VectorStore`, `VectorIndexManager` 구현
- [ ] `<Db>Properties`에 dimension, metric, index별 build/search 파라미터 기본값
- [ ] `@ConditionalOnProperty(prefix="vector.store", name="type", havingValue="<db>")` Config
- [ ] `application-<db>.yml` 프로필과 `benchmark.container-names`
- [ ] `docker-compose.yml`에 서비스 + `cpus` / `mem_limit` / `memswap_limit`
- [ ] `VectorIndexManager.searchParameterName()`과 허용 범위 구현
- [ ] `scripts/run-all-benchmarks.ps1`의 `Start-Database`, `Assert-DatabaseResourceBudget`, `Stop-Database`
- [ ] `docs/05-databases/<db>.md`
- [ ] 샘플 데이터로 smoke test 통과

## 관련 문서

- [vector-store.md](vector-store.md)
- [code-architecture.md](code-architecture.md)
