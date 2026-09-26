# Benchmark Results — 비교 모집단 수정 후 재실행 (2026-09-11)

> **현재 기준 안내 (2026-09-13):** 최신 결과는 [fairness-v2 620개 측정·6축 산포도](fairness-v2-results-20260913.md)다. 아래는 2026-09-11 당시 실행·해석을 보존한 역사 자료이며 수치와 판정 표현을 최신 결과에 적용하지 않는다. 채점·측정·어댑터 조건이 달라 최신 620개와 합산하지 않는다.

> **역사 자료:** 이 결과는 단일 재구축이며 같은 query로 튜닝·평가했습니다. 현재 하네스의
> calibration/evaluation split과 반복 집계 결과가 아니므로 제품 선정 근거에서 제외합니다.

> **상태: 재실행 완료.** Qdrant payload index, 필터/무필터 percentile 분리,
> 목표 Recall의 비교 모집단, PostgreSQL Source of Truth 동기화를 반영한 결과다.
> 단일 재구축 1회이므로 최종 제품 선정 전 반복 실행이 필요하다.

## 실행 조건

- 입력: BGE-M3 dense 1024차원, chunk 10,000건, 질의 300건
- PostgreSQL Source of Truth: 원문 200건 / chunk 10,000건, 입력 SHA-256 검증
- 거리 함수: cosine, Top-K: 10
- 주요 Target Recall@10: 0.80 / 0.90 / 0.95, 허용오차 ±0.01
- 비교 모집단: 무필터 질의 270건. 전체 300건 Recall은 별도 참고값
- 목표 범위가 없을 때: 가장 가까운 비교 Recall과 `CLOSEST_AVAILABLE` 기록
- 부하: concurrency 10, warm-up 1회, 측정 5회
- DB·목표별 본 측정: 1,500회(필터 150회, 무필터 1,350회)
- Vector DB 배포 예산: 대상 합계 4 vCPU / 8 GiB / swap 없음
- 결과 경로: `benchmark-result/rerun-unfiltered-recall-final3-20260911`

문서 벡터 SHA-256은
`cc23f095b88585453940e8fca998f8b16f9573361b0c83f021d7bb095ba9e4c6`,
질의 벡터 SHA-256은
`d06cc81c1169e1a926fc69a835fabda560932d8834bd687893fe0da301c4cfa9`다.

## 검색 결과

`비교 Recall`과 `target_met`은 무필터 질의 기준이다. ANN 인덱스 성능은 같은 모집단의
`무필터 p95`로 비교한다. QPS는 필터 10%를 포함한 고정 혼합 전체의 처리량이며,
합산 Recall·p95도 같은 서비스 혼합의 참고값이다.

| DB | 목표 | 비교 Recall | 충족 | 선택 | 파라미터 | 무필터 p95 ms | 필터 p95 ms | QPS | RAM MiB |
|---|---:|---:|:---:|---|---|---:|---:|---:|---:|
| pgvector | 0.80 | 0.8137 | X | CLOSEST_AVAILABLE | ef_search=40 | 5.03 | 5.07 | 3,044.77 | 193.2 |
| pgvector | 0.90 | 0.8800 | X | CLOSEST_AVAILABLE | ef_search=120 | 5.73 | 5.35 | 2,680.32 | 193.1 |
| pgvector | 0.95 | 0.9489 | O | WITHIN_TOLERANCE | ef_search=400 | 31.05 | 29.66 | 1,485.78 | 194.6 |
| Qdrant | 0.80 | 0.9330 | X | CLOSEST_AVAILABLE | hnsw_ef=10 | 7.08 | 7.30 | 2,325.27 | 103.2 |
| Qdrant | 0.90 | 0.9330 | X | CLOSEST_AVAILABLE | hnsw_ef=10 | 5.96 | 5.84 | 2,660.22 | 103.3 |
| Qdrant | 0.95 | 0.9522 | O | WITHIN_TOLERANCE | hnsw_ef=20 | 5.62 | 5.44 | 2,751.52 | 101.3 |
| Weaviate | 0.80 | 0.8037 | O | WITHIN_TOLERANCE | ef=40 | 31.31 | 32.39 | 625.53 | 447.8 |
| Weaviate | 0.90 | 0.9078 | O | WITHIN_TOLERANCE | ef=200 | 34.29 | 37.52 | 585.57 | 438.6 |
| Weaviate | 0.95 | 0.9515 | O | WITHIN_TOLERANCE | ef=400 | 31.29 | 32.72 | 601.10 | 294.3 |
| Milvus | 0.80 | 0.8959 | X | CLOSEST_AVAILABLE | ef=40 | 29.53 | 37.97 | 1,081.79 | 672.3 |
| Milvus | 0.90 | 0.8959 | O | CLOSEST_AVAILABLE* | ef=40 | 24.68 | 38.75 | 1,191.28 | 673.9 |
| Milvus | 0.95 | 0.7422 | X | WITHIN_TOLERANCE* | ef=10 | 30.21 | 39.90 | 1,103.26 | 710.9 |
| OpenSearch | 0.80 | 0.9296 | X | CLOSEST_AVAILABLE | ef_search=10 | 23.93 | 29.17 | 1,421.20 | 5,122.0 |
| OpenSearch | 0.90 | 0.9296 | X | CLOSEST_AVAILABLE | ef_search=10 | 32.06 | 34.28 | 1,285.85 | 5,123.1 |
| OpenSearch | 0.95 | 0.9493 | O | WITHIN_TOLERANCE | ef_search=40 | 16.75 | 8.61 | 1,696.75 | 5,123.1 |

`*` Milvus 0.90은 튜닝 0.9138로 범위 밖이었지만 본 측정은 0.8959로 들어왔고,
0.95는 튜닝 0.9578에서 본 측정 0.7422로 벗어났다. 두 행 모두 튜닝과 본 측정 판정이
일치하지 않으므로 엄격한 직접 비교에서는 제외한다.

## 판정

1. 목표 0.80과 0.90은 엄격한 조건을 통과한 제품이 각각 Weaviate 하나뿐이라 DB 간
   직접 비교를 할 수 없다.
2. 목표 0.95는 pgvector, Qdrant, Weaviate, OpenSearch가 충족했다. Qdrant의 무필터
   p95 5.62 ms가 가장 낮았고, OpenSearch는 16.75 ms였지만 약 5.0 GiB를 선점했다.
3. Milvus 0.90과 0.95는 튜닝-본측정 drift 때문에 엄격 비교에서 제외한다. 후보 선택의 재현성과
   segment 상태를 반복 실행으로 추가 검증해야 한다.
4. CPU 수치는 전체 측정이 Docker의 약 1초 집계창보다 짧은 행에서 과소 집계되므로
   이 표의 제품 선택 근거에서 제외했다.

## 검증 결과

- Qdrant payload index 6개 생성 및 exact-scan 대기 생략 경로까지 존재 검증
- CSV 15행 / 42열, 필터·무필터 지표 누락 0건
- 유효한 행: `recall_selection=WITHIN_TOLERANCE`와 `target_met=true`를 모두 만족한 6행
- SVG: x축 무필터 p95, y축 무필터 `comparison_recall`
- PostgreSQL: Flyway baseline 0 + V1 + V2 성공, 원문 200건 / chunk 10,000건 / 해시 일치
- 애플리케이션 오류 로그 5개 모두 0바이트
- 실행 후 컨테이너: 벤치마크 DB는 모두 중지, 기존 Ollama만 유지

이전 `rerun-filter-segmented-20260911` 결과는 필터 지연 분리 확인에는 유효하지만,
전체 `actual_recall`과 무필터 p95를 짝지어 목표를 판정했으므로 최종 비교표에서는 대체했다.

0.70과 0.99는 본실험에서 제외했다. 필요할 때
`data/benchmark-request-auxiliary.json`을 별도 결과 디렉터리로 실행한다.
