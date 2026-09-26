# OpenSearch

현재 matrix는 네 조합을 분리합니다.

| Test | Engine | Index | Search 폭 |
|---|---|---|---|
| T21 | Lucene | HNSW | candidate_k |
| T23 | Faiss | HNSW | ef_search |
| T25 | Faiss | IVF | nprobes |
| T27 | JVector | DiskANN | candidate_k |

## Lucene HNSW

Lucene engine은 `ef_search`를 무시하고 query의 `k`를 traversal 폭으로 사용합니다. 하네스는 응답 `size=10`은 고정한 채 candidate `k` 그리드를 전부 측정합니다. 존재하지 않는 ef 값을 결과에 기록하지 않습니다.

## Faiss

Faiss HNSW는 `method_parameters.ef_search`를 조정합니다. Faiss IVF는 먼저 training index에 벡터를 올리고 model API로 `nlist=128` model을 학습한 뒤, model ID를 참조하는 대상 index를 생성합니다. 검색 폭은 `nprobes`입니다.

적재 batch마다 refresh하지 않고 전체 적재 후 refresh와 force-merge를 수행한 뒤 측정합니다.

## JVector DiskANN

JVector plugin은 opensearch-knn과 같은 node에서 공존할 수 없으므로 `docker/opensearch-jvector/Dockerfile`로 별도 이미지를 만들고 port 19200의 별도 service/volume을 사용합니다. candidate `k`를 조정하고 top-10만 반환합니다.

## 자원과 크기

두 OpenSearch service 모두 4 vCPU/8GiB, JVM heap 4GiB입니다. heap 선점 때문에 관측 RAM을 순수 live working set으로 해석하지 않습니다. 4GiB 힙은 이번 실행의 기준 설정이고 기존 `DecisionGate`의 2GiB 탈락 기본값은 합의된 선정 조건이 아니므로 적용하지 않습니다. `_stats/store` 크기는 OpenSearch가 보고하는 index store 범위이지 순수 ANN 구조 크기가 아닙니다.

## 최신 fairness-v2 실측 — 2026-09-13 완료

[최신 620개 결과](../07-results/fairness-v2-results-20260913.md) 중 이 DB의 실측입니다. 범위는 **모든 검색 파라미터와 다섯 재구축 회차**의 혼합 지표입니다. 최소 p95와 최대 Recall이 같은 점이라는 뜻은 아닙니다. 합성 10k·1024차원·동시성 10 조건입니다.

| 구성 | 실제 검색 그리드 | 점 수 | Recall@10 범위 | 전체 p95 ms 범위 |
|---|---|---:|---:|---:|
| T21 / Lucene / HNSW | candidate_k: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 45 | 0.787629–0.995361 | 4.771–17.264 |
| T23 / Faiss / HNSW | ef_search: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 45 | 0.745876–1.000000 | 4.771–19.370 |
| T25 / Faiss / IVF | nprobes: 1, 2, 4, 8, 16, 32, 64, 96, 128 | 45 | 0.712371–1.000000 | 4.784–40.791 |
| T27 / JVector / DiskANN | candidate_k: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 45 | 0.352062–0.996392 | 4.693–90.115 |

180개 중 워밍업 미달 32개를 포함해 모두 보존합니다. [최신 다축 산포도](../07-results/fairness-v2-results-20260913.md)에서 engine까지 구분하고 같은 Recall 수준의 설정끼리 5회 산포·CPU·RAM·QPS를 비교합니다. RAM 자체는 관측 지표이며 2GiB 초과로 탈락시키지 않습니다. [2026-09-11 v1 결과](../07-results/sweep-results-20260911.md)는 역사 자료입니다.

## 참고

- [OpenSearch k-NN methods and engines](https://docs.opensearch.org/latest/mappings/supported-field-types/knn-methods-engines/)
- [OpenSearch approximate k-NN과 IVF training](https://docs.opensearch.org/latest/vector-search/vector-search-techniques/approximate-knn/)
- [OpenSearch JVector plugin](https://docs.opensearch.org/latest/install-and-configure/additional-plugins/opensearch-jvector/)
