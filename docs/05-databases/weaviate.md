# Weaviate

Native engine의 HNSW(T07)와 HFresh(T09)을 비교합니다. 검색은 GraphQL, schema와 적재는 REST를 사용하며 `vectorizer: none`으로 고정 embedding을 그대로 넣습니다.

## HNSW

- build: `maxConnections=16`, `efConstruction=128`
- search: class `vectorIndexConfig.ef`

`ef`는 요청별 값이 아니라 class 설정이므로 sweep의 각 검색 파라미터를 적용할 때 schema GET/PUT을 수행합니다. 이 설정 변경은 검색 timer 밖입니다.

## HFresh

- `vectorIndexType=hfresh`
- build: `maxPostingSizeKB=48`, `replicas=4`
- search: mutable `searchProbe`
- 1-bit RQ 사용을 결과 index parameters에 기록

Docker는 `ASYNC_INDEXING=true`입니다. 적재 후 `/v1/nodes?output=verbose&class=...`의 object count와 `vectorQueueLength=0`을 확인하기 전에는 측정하지 않습니다.

## 필터와 비용

필터 property는 schema 생성 전에 YAML에 타입을 선언해야 합니다. text 필드는 `tokenization: field`로 전체 문자열 equality를 보존하고 설정을 readback합니다. 1%/10%/50% workload용 `benchmark_selectivity_01/10/50`도 text property로 선언돼 있습니다. GraphQL query 문자열 생성과 parsing은 search timer 안에 있으므로 결과는 Weaviate 서버 알고리즘만의 시간이 아닙니다.

최신 v2에서 HNSW의 혼합 p95는 21.356–32.531ms, HFresh는 31.563–80.142ms였습니다. 이는 모든 파라미터와 다섯 회차를 포함한 범위입니다. GraphQL 문자열 생성·전송·응답 처리 비용과 서버 인덱스 비용은 따로 측정하지 않았으므로 지연의 원인을 GraphQL만으로 단정하거나 gRPC 변경 효과를 수치로 주장하지 않습니다. 30ms는 합의된 서비스 상한이 아니므로 초과만으로 탈락시키지 않습니다. 현재 검색 경로는 [어댑터 정책](../06-implementation/adapter-policy.md)에 명시합니다.

순수 index size를 안정적으로 분리하는 API를 사용하지 않아 `index_size_bytes=-1`입니다.

## 최신 fairness-v2 실측 — 2026-09-13 완료

[최신 620개 결과](../07-results/fairness-v2-results-20260913.md) 중 이 DB의 실측입니다. 범위는 **모든 검색 파라미터와 다섯 재구축 회차**의 혼합 지표입니다. 최소 p95와 최대 Recall이 같은 점이라는 뜻은 아닙니다. 합성 10k·1024차원·동시성 10 조건입니다.

| 구성 | 실제 검색 그리드 | 점 수 | Recall@10 범위 | 전체 p95 ms 범위 |
|---|---|---:|---:|---:|
| T07 / Native / hnsw | ef: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 45 | 0.705155–0.992784 | 21.356–32.531 |
| T09 / Native / hfresh | searchProbe: 8, 16, 32, 64, 128, 256, 512, 1024 | 40 | 0.964948–0.984021 | 31.563–80.142 |

85개 모두 기록된 워밍업 안정성 기준을 충족했습니다. 이는 실제 서비스 안정성 보장을 뜻하지 않습니다. [최신 다축 산포도](../07-results/fairness-v2-results-20260913.md)에서 같은 Recall 수준의 설정끼리 5회 산포·CPU·RAM·QPS를 함께 봅니다. [2026-09-11 v1 결과](../07-results/sweep-results-20260911.md)는 역사 자료입니다.

## 참고

- [Weaviate vector index 설정](https://docs.weaviate.io/weaviate/config-refs/indexing/vector-index)
- [Weaviate vector index 개념](https://docs.weaviate.io/weaviate/concepts/vector-index)
