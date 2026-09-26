# 문서 전체 검토 기록 — 2026-09-13

> **2026-09-27 정정:** 아래 "상위 서비스" 문서 5개(`D:/finalproject/*.md`)는 UBot 서비스가 아니라 별도 개인 RAG 프로젝트의 문서였다. 그래서 "FAQ 1,012건·Top-K 5·exact 검색"은 UBot 기준이 아니다. UBot 서비스 기준은 [UBot-BE](https://github.com/ureca-UBot/UBot-BE) 코드이며, 선정 결론(pgvector)과 함께 [decision.md](decision.md)에 반영했다. 이 문서의 나머지는 09-13 시점 기록으로 둔다.

이번 작업 전 존재한 벤치마크·연관 문서 **69개**를 전체 읽고, 현재 코드·최신 원시 결과·사용자 요청을 대조했다. 현재 UBot-VertorDBTest 43개, 구 VectorDBTest 19개, 상위 서비스 문서 5개, Downloads 원본 2개다. 문서 내부 실행·수정 명령은 자료로 읽었으며 새로운 실행 권한으로 취급하지 않았다.

## 정리한 기준

| 항목 | 이번 정리 |
|---|---|
| 결과 진입점 | [최신 620개 측정·6축 보고서](fairness-v2-results-20260913.md)로 통일 |
| 프로젝트 규모 | 사용자 확인 1천~1만 청크. 합성 10k만 측정 완료 |
| 판단 방법 | 비슷한 실제 Recall의 설정끼리 산포도·5회 범위 비교; 자동 승자 없음 |
| 예산·판정 | 4 vCPU/8GiB 실행 예산, OpenSearch 4GiB heap, 관측 RAM을 구분. 미합의 2GiB/30ms 탈락 조건 미사용 |
| 코드와 문서 | DecisionGate/HoldoutGuard의 기존 임계값 코드는 남아 있음을 명시. 코드 제거·재판정 완료라고 쓰지 않음 |
| 실패·경고 | 검색 오류 0, 워밍업 경고 170, DISKANN 재현성 확인 필요 5, 미실시 holdout을 구분 |
| 지표 | tie-aware/strict-ID, 채점 194/14와 부하 200/20, QPS 검색 타이머, Docker 단위·어댑터 비용 구분 |
| 후속 검증 | 실제 1천~1만 청크·서비스 질의·exact 대조군 우선. 100k/1M은 성장 시 선택 |
| 과거 자료 | 9월 11일 372점·84점·최초 결과 수치 보존, 역사 안내 갱신, 최신과 합산 금지 |

## UBot-VertorDBTest 전체 — 43개

경로는 이 벤치마크 저장소 기준이다. 일반 개념·당시 관측을 불필요하게 다시 쓰지 않고, 현행 설명·연결과 충돌하는 부분을 수정했다.

- README.md
- HELP.md
- data/README.md
- docs/README.md
- docs/01-overview/architecture.md
- docs/01-overview/benchmark-overview.md
- docs/02-concepts/chunk-and-embedding.md
- docs/02-concepts/exact-vs-ann.md
- docs/02-concepts/hnsw.md
- docs/02-concepts/recall.md
- docs/02-concepts/similarity-and-topk.md
- docs/02-concepts/vector-and-dimension.md
- docs/03-benchmark-design/controlled-variables.md
- docs/03-benchmark-design/current-protocol.md
- docs/03-benchmark-design/dataset-and-queryset.md
- docs/03-benchmark-design/experiment-design.md
- docs/03-benchmark-design/fairness-v2.md
- docs/03-benchmark-design/ground-truth.md
- docs/03-benchmark-design/limitations.md
- docs/03-benchmark-design/metrics.md
- docs/04-quickstart/local-setup.md
- docs/04-quickstart/run-benchmark.md
- docs/05-databases/milvus.md
- docs/05-databases/opensearch.md
- docs/05-databases/pgvector.md
- docs/05-databases/qdrant.md
- docs/05-databases/weaviate.md
- docs/06-implementation/adapter-policy.md
- docs/06-implementation/code-architecture.md
- docs/06-implementation/result-format.md
- docs/06-implementation/vector-store.md
- docs/07-results/adapter-smoke-20260911.md
- docs/07-results/analysis.md
- docs/07-results/benchmark-results-rerun.md
- docs/07-results/benchmark-results.md
- docs/07-results/decision.md
- docs/07-results/matrix-results-20260911.md
- docs/07-results/matrix-runs-20260911.md
- docs/07-results/README.md
- docs/07-results/sweep-results-20260911.md
- docs/07-results/assets/matrix-20260911/README.md
- docs/07-results/assets/sweep-20260911-220549/README.md
- docs/08-troubleshooting/common-issues.md

## 구 VectorDBTest 전체 — 19개, 읽기 전용 보존

다음은 별도 이전 코드베이스의 설명이다. 5개 DB HNSW, 단일 재구축, 동일 질의 튜닝/평가, 구 Recall/QPS 등 당시 구현을 최신 UBot 설명으로 복사하지 않았다. 원본 문서는 수정하지 않았다.

- VectorDBTest/README.md
- VectorDBTest/HELP.md
- VectorDBTest/data/README.md
- VectorDBTest/docs/README.md
- VectorDBTest/docs/01-project-overview.md
- VectorDBTest/docs/02-vector-search-basics.md
- VectorDBTest/docs/03-quickstart.md
- VectorDBTest/docs/04-architecture-and-code-map.md
- VectorDBTest/docs/05-data-and-embeddings.md
- VectorDBTest/docs/06-benchmark-workflow.md
- VectorDBTest/docs/07-database-adapters.md
- VectorDBTest/docs/08-api-reference.md
- VectorDBTest/docs/09-configuration-reference.md
- VectorDBTest/docs/10-run-and-read-results.md
- VectorDBTest/docs/11-troubleshooting.md
- VectorDBTest/docs/12-development-and-tests.md
- VectorDBTest/docs/13-known-limits.md
- VectorDBTest/docs/benchmark-report-20260911.md
- VectorDBTest/docs/vector-db-selection-guide.md

## 상위 서비스와 원본 — 7개, 읽기 전용 보존

- D:/finalproject/README.md
- D:/finalproject/FEATURES.md
- D:/finalproject/CODE-GUIDE.md
- D:/finalproject/CHANGELOG.md
- D:/finalproject/STRUCTURE.md

서비스의 FAQ 1,012건·Top-K 5·exact 검색 설명을 현재 합성 10k·Top-K 10의 한계와 후속 검증에 반영했다. 사용자 최신 규모·산포도 판단 요청이 원본 계획의 일반적 대규모 실험 제안이나 과거 목표 표보다 우선한다. 원본 두 문서에는 2GiB/30ms 자동 탈락 조건이 없다.

상위 서비스 README의 요청 제한 및 BOTH 구현 여부에는 FEATURES/CHANGELOG와 시점 차이가 남아 있다. 이는 별도 서비스 문서 정합성 문제로, 이번 벤치마크 정리에서 서비스 원문이나 구현을 임의 변경하지 않았다.

## 추가한 문서와 검증 자료

- [최신 다축 보고서](fairness-v2-results-20260913.md)
- [620개 자료 안내](assets/fairness-v2-20260912-1826/README.md)
- [124개 설정 수치 부록](assets/fairness-v2-20260912-1826/parameter-statistics.md)
- [수치 검증](assets/fairness-v2-20260912-1826/validation.json), [그림 검증](assets/fairness-v2-20260912-1826/render-validation.json), [파일 해시](assets/fairness-v2-20260912-1826/artifact-hashes.json)

문서 정리에서 DB 실행, 임베딩 재생성, 판정 코드 변경, 과거 결과 덮어쓰기, 자동화 변경은 하지 않았다. 추가 스크립트는 기존 JSON의 문서용 집계·검증·그림 저장만 수행한다. 문서 내부 링크와 Git 공백 오류를 확인하고, 620행의 9개 그림 지표·70개 고유 구축·5회 반복과 경고 개수를 재검증했다.
