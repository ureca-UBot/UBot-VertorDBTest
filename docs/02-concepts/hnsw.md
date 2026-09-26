# HNSW

이 프로젝트가 다섯 DB에서 공통으로 사용하는 ANN 인덱스입니다.

## 구조

Hierarchical Navigable Small World. 벡터를 노드로, 이웃 관계를 간선으로 하는 그래프를 계층으로 쌓습니다.

```text
Layer 2    ●───────────────●          성긴 그래프, 멀리 점프
           │               │
Layer 1    ●───●───────●───●          중간
           │   │       │   │
Layer 0    ●─●─●─●─●─●─●─●─●          전체 벡터, 촘촘한 이웃
```

검색은 위층에서 대략적인 위치로 점프한 뒤 아래층에서 정밀하게 좁힙니다.
전수 비교 없이 가까운 이웃에 도달할 수 있습니다.

## 파라미터

| 파라미터 | 시점 | 의미 | 올리면 |
|---|---|---|---|
| `M` | 인덱스 생성 | 노드당 이웃 수 | 정확도 ↑, 메모리 ↑, 빌드 시간 ↑ |
| `ef_construction` | 인덱스 생성 | 생성 시 탐색 후보 수 | 그래프 품질 ↑, 빌드 시간 ↑ |
| `ef_search` | 검색 | 검색 시 탐색 후보 수 | Recall ↑, latency ↑ |

**생성 파라미터를 바꾸면 재구축이 필요하고, 검색 폭은 재구축 없이 바꿀 수 있습니다.** 적용 위치는 요청·세션·클래스 설정으로 제품마다 다릅니다. 위 화살표는 일반적인 경향이며 모든 실측 점의 단조 증가를 보장하지 않습니다.

## 이 프로젝트의 고정값

주 행렬의 HNSW 생성 파라미터는 다음 값으로 맞춥니다. IVF·HFresh·DISKANN에는 별도 생성 설정을 사용합니다.

```text
M = 16
ef_construction = 128
```

검색 파라미터는 DB마다 이름이 다릅니다.

| DB | 검색 파라미터 이름 | 적용 방식 |
|---|---|---|
| pgvector | `ef_search` | 검색 세션에 `set_config('hnsw.ef_search', ...)` |
| Qdrant | `hnsw_ef` | 요청 body의 `params` |
| Weaviate | `ef` | 클래스 스키마 갱신 (요청별 지정 불가) |
| Milvus | `ef` | 요청 body의 `searchParams.params` |
| OpenSearch Faiss HNSW | `ef_search` | knn 쿼리의 `method_parameters` |
| OpenSearch Lucene HNSW | `candidate_k` | 요청 후보 `k` 변경, 반환 Top-10 유지 |

`VectorIndexManager.searchParameterName()`이 엔진별 키를 제공합니다. OpenSearch Lucene/JVector는 `candidate_k`, Faiss HNSW는 `ef_search`를 사용합니다.

## 전체 파라미터 sweep

`searchParameters: {}`이면 `searchParameterValues` 또는 기본 그리드 전체를 측정합니다. 예를 들어 `[16, 32, 64, 96, 128]`에서 각 값의 Recall·latency·QPS·CPU·RAM을 모두 저장합니다. 아래 `repetitions: 3`은 호출 형식 예시이며 현재 기본값이나 완료 실행의 횟수가 아닙니다. [fairness-v2 완료 실행](../07-results/fairness-v2-results-20260913.md)은 전체 재구축 5회이고, 현재 API도 `rebuildAndLoad=true`일 때 매 repetition 재구축합니다.

```json
{"searchParameters": {}, "searchParameterValues": [16, 32, 64, 96, 128], "repetitions": 3}
```

Recall이 0.90·0.95를 넘더라도 측정을 계속합니다. 단조성 보정이나 목표 구간 선택을 적용하지 않습니다. 모든 실제 점을 X=p95, Y=Recall에 그리고 0.90·0.95를 참고선으로 표시합니다.

고정 파라미터만 반복할 때는 그리드를 생략합니다.

```json
{"searchParameters": {"ef_search": 120}, "repetitions": 3}
```

Weaviate는 검색 폭을 클래스 스키마로 적용하므로 파라미터별 실행은 순차적으로 진행합니다. 한 파라미터 안의 질의만 지정한 동시성으로 처리합니다.

## 주의: 비동기 인덱싱

여러 DB가 적재 직후 인덱스를 아직 만들지 않은 상태로 검색을 받습니다.
그 상태로 측정하면 HNSW가 아니라 exact scan을 측정하게 됩니다.

`VectorIndexManager.awaitReady()`가 제품별 장벽을 담당합니다.

- **Qdrant** — `indexed_vectors_count`가 전체 건수에 도달하고 status가 `green`이 될 때까지 대기.
  exact-scan 임계값 아래에서는 이 건수 대기만 생략하고 payload index 검증은 수행
- **Milvus** — flush·index·load 상태와 query-node의 Sealed/Flushed segment row 합계를 확인
- **OpenSearch** — 전체 적재 뒤 refresh와 force-merge 완료를 기다림
- **Weaviate** — object count와 비동기 인덱싱의 `vectorQueueLength=0` 확인
- **pgvector** — 적재 건수 확인; IVFFlat은 적재 후 인덱스를 생성하며 HNSW는 생성한 인덱스에 적재

준비 장벽과 본 측정 중의 재현성은 다른 확인입니다. 특히 Milvus의 사후 `verified=true`가 모든 측정 응답의 안정성을 보증하지 않는 점은 [현재 한계](../03-benchmark-design/limitations.md)를 봅니다.

## 주의: ef를 올려도 느려지지 않는 구간

`ef`를 올려도 특정 percentile이 비슷하면 파라미터 적용, 필터 경로, 준비 상태를 확인합니다. 검색 품질 포화, 작은 데이터, 클라이언트 비용과 반복 변동도 영향을 줄 수 있으므로 latency만으로 HNSW 미사용을 단정하지 않습니다.

실제 사례는 [../07-results/analysis.md](../07-results/analysis.md)에 있습니다.

## 관련 문서

- [exact-vs-ann.md](exact-vs-ann.md)
- [recall.md](recall.md)
- [../05-databases/](../05-databases/)
