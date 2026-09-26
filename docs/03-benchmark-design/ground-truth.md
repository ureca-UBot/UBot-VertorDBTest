# Ground Truth

Recall 계산의 정답지입니다.

현재 [fairness-v2 실측](../07-results/fairness-v2-results-20260913.md)은 K위 경계 동점을 인정하는 Recall을 사용하며 과거 strict-ID Recall도 별도 기록합니다. 아래 Top-K 정렬 자체와 실제 채점 규칙을 구분해야 합니다.

## DB가 아니라 Java로 만드는 이유

각 DB에도 exact 검색 모드가 있지만 쓰지 않습니다.

- DB의 exact 구현에 문제가 있으면 정답지 자체가 오염됩니다.
- 제품마다 exact의 의미와 필터 적용 순서가 다를 수 있습니다.
- 정답지가 DB에 의존하면 "DB A의 정답으로 DB B를 채점"하는 상황이 생깁니다.

`ExactSearchEngine`은 적재한 것과 동일한 JSONL 벡터를 메모리에 올려 전수 비교합니다.
DB 구현과 완전히 독립적입니다.

## 계산 방식

```java
for (IndexedDocument indexed : documents) {
    if (!matches(document, request.filter())) continue;   // 1. 필터 먼저
    double score = similarity(queryVector, queryNorm, indexed.embedding(), indexed.norm());
    // 2. 상위 K개 유지 (min-heap)
}
return top.stream().sorted(BEST_FIRST).toList();          // 3. 결정론적 정렬
```

핵심은 순서입니다. **필터를 먼저 적용한 뒤 유사도로 Top-K를 뽑습니다.**
"필터를 만족하는 문서 중 가장 가까운 10개"가 정답입니다.

DB가 ANN으로 10개를 먼저 뽑고 그다음 필터를 적용하면(post-filtering) 결과가 10개보다 적어지고
Recall이 떨어집니다. 그 차이가 지표에 드러나야 하므로 정답지는 반드시 pre-filtering이어야 합니다.

## 정렬 안정성

```java
Comparator.comparingDouble(VectorSearchResult::score).reversed()
        .thenComparing(VectorSearchResult::id);
```

score 동점 시 id 오름차순으로 결정론적 순서를 만듭니다.
같은 입력이면 항상 같은 정답지가 나옵니다.

다만 K위에서 동점인 문서가 여러 개일 때 ID 정렬로 선택한 10개만 유일한 정답으로 취급하면 같은 거리의 다른 문서를 반환한 ANN을 부당하게 감점합니다. v2의 `ExactGroundTruth`는 결정론적 `strictTopK` 외에 원본 벡터의 K위보다 좋은 `strictlyBetterIds`와 경계 동점 전체 `boundaryIds`를 저장합니다. DB가 반환한 반올림 score로 동점을 판단하지 않습니다.

```text
A = K위보다 좋은 ID 집합, B = K위 경계 동점 ID 집합, R = 반환 ID 집합
K = min(요청 topK, 필터를 만족하는 문서 수)
tie-aware Recall@K = (|R∩A| + min(K−|A|, |R∩B|)) / K
```

K=0은 Recall 평균에서 제외합니다. 동점 허용 오차 기본값은 0이며 이번 입력에서 사용한 값도 0입니다. 더 좋은 이웃을 놓친 것을 동점 여러 개로 보상하지 않도록 동점 점수를 남은 자릿수로 제한합니다. strict-ID 교집합 Recall은 `filtered.strictIdRecall`과 `unfiltered.strictIdRecall`에서 따로 확인합니다.

## 성능 최적화

각 문서의 L2 노름을 생성 시점에 한 번만 계산해 보관합니다.

```java
this.documents = documents.stream().map(document -> {
    float[] embedding = document.embedding();
    return new IndexedDocument(document, embedding, norm(embedding));
}).toList();
```

cosine 계산에서 문서 노름을 매번 다시 구하지 않습니다.
정답지 계산은 타이머 밖이지만, 300질의 × 10,000문서 × 1024차원이라 무시할 수 없는 비용입니다.

## 캐시

Ground Truth는 `topK`별로 한 번만 계산해 재사용합니다.

```java
Map<Integer, Map<String, ExactGroundTruth>> groundTruthByTopK = new LinkedHashMap<>();
...
groundTruthByTopK.computeIfAbsent(scenario.topK(), ignored -> exactGroundTruth(...));
```

현재 행렬은 모두 `topK=10`이므로 한 번의 `BenchmarkRunner.run()` 호출 안에서 같은 정답지를 재사용합니다. 외부 실행 스크립트가 다음 구성을 시작하면 해당 호출에서 다시 계산합니다. 파라미터마다 정답을 바꾸지 않습니다.

## 산출물

```text
benchmark-result/<dir>/raw/ground-truth-top10.jsonl
```

```json
{"queryId":"q-001","topK":10,"truth":{"strictTopK":[...],"strictlyBetterIds":[...],"boundaryIds":[...],"boundaryScore":0.75,"tieTolerance":0.0}}
```

위는 구조 설명용이며 실제 score·ID는 원시 파일을 확인합니다. 이 정답과 `raw/query-audit-*.jsonl.gz`의 반환 ID·실행 횟수를 함께 사용하면 Recall을 재검산할 수 있습니다. 과거 `topK` ID 목록만 있는 정답 파일은 별도 형식입니다.

## 정답이 비어 있는 질의

필터가 어떤 문서와도 매칭되지 않으면 exact 정답이 0개입니다. 제공 데이터에서는
evaluation 필터 질의 20개 중 6개, calibration 1개가 여기 해당합니다.

이런 질의는 **재현할 순위가 없으므로 Recall 평균에서 뺍니다.** DB도 빈 결과를 반환했는지 별도 기록합니다.

행을 반환하면 Exact 필터 조건과의 불일치로 `empty_ground_truth_violations`를 증가시킵니다. 이는 Recall 목표 판정이 아니며 해당 측정 점은 계속 보존합니다.
Recall 평균에 1.0으로 섞으면 이 결함이 만점으로 기록됩니다.

`*_scored_queries`, `*_empty_ground_truth_queries`, `*_empty_ground_truth_violations` 세 컬럼에
구간별로 근거가 남습니다. [result-format.md](../06-implementation/result-format.md)를 봅니다.

## 신뢰 조건

정답지가 유효하려면 **DB에 적재된 벡터와 정답지를 만든 벡터가 같아야** 합니다.
`BenchmarkRunner`가 적재 직후 확인합니다.

```java
long storedVectors = store.count();
if (storedVectors != documents.size()) {
    throw new IllegalStateException("Vector count mismatch: ...");
}
```

건수가 다르면 즉시 중단합니다. 적재 실패를 Recall 저하로 오인하지 않기 위해서입니다.

## 관련 문서

- [../02-concepts/recall.md](../02-concepts/recall.md)
- [../02-concepts/exact-vs-ann.md](../02-concepts/exact-vs-ann.md)
