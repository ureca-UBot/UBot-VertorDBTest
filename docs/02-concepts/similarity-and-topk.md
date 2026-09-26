# Similarity와 Top-K

## Similarity

두 벡터가 얼마나 가까운지를 수치로 만든 값입니다. 이 프로젝트는 **cosine**을 사용합니다.

| Metric | 의미 | 이 저장소의 score 변환 |
|---|---|---|
| COSINE | 두 벡터가 이루는 각도. 크기 무시 | 그대로 사용 (1에 가까울수록 유사) |
| DOT | 내적. 크기 반영 | 그대로 사용 |
| EUCLIDEAN | 직선 거리. 작을수록 유사 | `-거리`로 부호 반전 |

`ExactSearchEngine`과 각 어댑터는 **높을수록 좋은 score**로 통일합니다.
그래야 Top-K 정렬 로직을 DB마다 다르게 쓰지 않아도 됩니다.

## 왜 cosine인가

BGE-M3 출력이 L2 정규화되어 있어 cosine과 dot의 순위가 사실상 같습니다.
그중 cosine을 고른 이유는 다섯 DB가 모두 1급으로 지원하는 metric이기 때문입니다.

중요한 것은 어떤 metric인지가 아니라 **전 DB가 같은 metric을 쓰는 것**입니다.
`BenchmarkRunner`가 실행 전에 검사하고, 다르면 실행을 거부합니다.

```java
if (properties.getMetric() != store.metric()) {
    throw new IllegalArgumentException("Benchmark metric ... does not match active store metric ...");
}
```

DB별 metric 이름 매핑:

| DB | cosine 표기 |
|---|---|
| pgvector | `vector_cosine_ops` / 연산자 `<=>` |
| Qdrant | `Cosine` |
| Weaviate | `cosine` |
| Milvus | `COSINE` |
| OpenSearch | `cosinesimil` |

## Top-K

질의 하나에 대해 가장 유사한 K개를 반환합니다. 이 프로젝트의 **K는 10**입니다.

K는 두 곳에서 동시에 의미를 가집니다.

- 검색 결과 개수 — DB에 `limit 10`을 요청합니다.
- Recall 계산의 분모 — exact Top-10과 ANN Top-10을 비교합니다.

## 동점 처리

score가 같은 문서가 여러 개면 순서가 흔들려 Recall이 불안정해질 수 있습니다.
`ExactSearchEngine`은 score 내림차순 뒤 **id 오름차순**으로 결정론적 정렬을 합니다.

```java
Comparator.comparingDouble(VectorSearchResult::score).reversed()
        .thenComparing(VectorSearchResult::id);
```

이것은 strict Top-K 목록의 정렬 규칙입니다. 현재 v2 Recall은 K위와 같은 원본 벡터 score의 다른 ID도 남은 자릿수만큼 정답으로 인정합니다. ID 정렬만으로 동점 문제가 해결됐다고 보지 않으며 [경계 동점 채점](../03-benchmark-design/ground-truth.md)을 함께 읽습니다.

## 필터가 있는 Top-K

필터가 있으면 "필터를 만족하는 문서 중 Top-10"이 정답입니다.
Ground Truth도 같은 필터를 적용해 계산합니다. 필터 없는 전체 Top-10과 비교하지 않습니다.

```java
for (IndexedDocument indexed : documents) {
    if (!matches(document, request.filter())) continue;   // 필터 먼저
    ... // 그 다음 유사도
}
```

이 순서가 중요합니다. DB 쪽에서 "ANN으로 10개 뽑은 뒤 필터"(post-filtering)를 하면
결과가 10개보다 적어지고 Recall이 떨어집니다. 그 차이가 지표에 드러나야 합니다.

## 관련 문서

- [exact-vs-ann.md](exact-vs-ann.md)
- [recall.md](recall.md)
- [../03-benchmark-design/ground-truth.md](../03-benchmark-design/ground-truth.md)
