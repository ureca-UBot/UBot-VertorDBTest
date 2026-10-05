# 문서 안내

**결론: UBot은 pgvector를 사용합니다.** 같은 Recall 수준에서 Qdrant·OpenSearch가 더 빨랐지만 차이가 절대적으로 크지 않아 이미 쓰는 PostgreSQL에 FAQ와 벡터를 함께 두는 편의성을 우선했습니다. 근거는 [선정 결론](07-results/decision.md)에 있습니다.

근거 측정은 **2026-09-13 완료된 fairness-v2의 620개 실측**입니다. 14개 DB·엔진·인덱스 구성을 각각 5회 독립 재구축했고(총 70개 build) 각 회차에서 124개 검색 설정을 측정했습니다. 실제 프로젝트의 예상 범위는 1,000~10,000개 청크이며 실측은 합성 10k 청크입니다.

## 결과부터 읽기

| 문서 | 확인할 내용 |
|---|---|
| [선정 결론: pgvector](07-results/decision.md) | 결정 근거 수치, 편의성, UBot-BE 현재 설정과 벤치마크 조건의 차이, 남은 확인 |
| [최신 620개 실측·다축 산포도 보고서](07-results/fairness-v2-results-20260913.md) | 무필터/혼합/필터 정확도·지연·QPS·CPU·RAM, 5회 산포와 경고 |
| [현재 실행 프로토콜](03-benchmark-design/fairness-v2.md) | 5회 재구축, 30초/30표본, 동점 Recall, 감사 기록 |
| [과거 v1 372개 보고서](07-results/sweep-results-20260911.md) | 3회·5초 조건의 역사 자료; 최신 v2와 합산하지 않음 |
| [과거 결과 목록](07-results/README.md) | 이전 목표별 선택 방식과 결함 분석의 보존 기록 |

## 설계와 실행 이해하기

1. [벤치마크 개요](01-overview/benchmark-overview.md)와 [아키텍처](01-overview/architecture.md)에서 측정 대상을 확인합니다.
2. [Recall](02-concepts/recall.md), [Exact와 ANN](02-concepts/exact-vs-ann.md), [HNSW](02-concepts/hnsw.md)에서 탐색 폭과 품질의 관계를 읽습니다.
3. [fairness-v2 프로토콜](03-benchmark-design/fairness-v2.md), [고정 조건](03-benchmark-design/controlled-variables.md), [실험 설계](03-benchmark-design/experiment-design.md)를 확인합니다.
4. [데이터와 질의](03-benchmark-design/dataset-and-queryset.md), [Ground Truth](03-benchmark-design/ground-truth.md), [측정 지표](03-benchmark-design/metrics.md), [한계](03-benchmark-design/limitations.md)를 함께 읽습니다.
5. [로컬 준비](04-quickstart/local-setup.md)와 [실행 방법](04-quickstart/run-benchmark.md)을 따라 새 결과 디렉터리에서 실행합니다.

## 구현과 문제 해결

- DB별 구성과 이번 실측: [pgvector](05-databases/pgvector.md), [Qdrant](05-databases/qdrant.md), [Weaviate](05-databases/weaviate.md), [Milvus](05-databases/milvus.md), [OpenSearch](05-databases/opensearch.md)
- 구현 계약: [VectorStore와 IndexManager](06-implementation/vector-store.md), [어댑터 정책](06-implementation/adapter-policy.md), [코드 지도](06-implementation/code-architecture.md)
- 측정값 확인: [결과 형식](06-implementation/result-format.md), [문제 해결](08-troubleshooting/common-issues.md)

이번 측정은 10k 합성 데이터·1024차원·Top-10·동시성 10·DB 합계 4 vCPU/8 GiB 조건입니다. OpenSearch 힙 4GiB는 이 실행 예산 안의 설정이고 RAM은 관측 지표입니다. 코드의 `DecisionGate` 기본 2GiB·30ms·0.95와 `eligible`는 코드에 남아 있지만 선정에 쓰지 않았습니다.

워밍업 미달 170건과 Milvus DISKANN 변동 5건을 표시한 채 결과를 보존합니다. 1k·실제 FAQ·별도 필터 선택도·독립 holdout은 측정하지 않았습니다. 실제 FAQ에서 확인할 항목은 [선정 결론의 남은 확인](07-results/decision.md#남은-확인)에 있습니다. 과거 84개·372개 결과는 각각의 프로토콜로만 읽습니다.

[프로젝트 README](../README.md)
