# Benchmark Overview

동일한 벡터·query·자원 조건에서 다섯 Vector DB의 검색 파라미터를 바꾸며 Recall·latency·자원 변화를 측정하고 산포도로 비교합니다. 현재 실행 기준은 [fairness-v2](../03-benchmark-design/fairness-v2.md)입니다. [최신 2026-09-13 결과](../07-results/fairness-v2-results-20260913.md)는 14개 구성·124개 검색 설정을 5회 독립 재구축한 620개 측정입니다.

프로젝트 규모는 1,000~10,000개 청크이며 현재는 합성 10k·1024차원·동시성 10만 측정했습니다. 6개 산포도로 같은 Recall 수준의 설정끼리 정확도·p95·QPS·CPU·RAM과 반복 범위를 비교합니다. 하네스는 자동 승자를 정하지 않으며 팀은 이 비교를 보고 **pgvector를 사용하기로 결정**했습니다. 근거는 [선정 결론](../07-results/decision.md)에 있습니다.

## 측정 항목

| 항목 | 정의 |
|---|---|
| comparison Recall@10 | evaluation 무필터 검색의 경계 동점 인정 Recall; 무필터가 없으면 전체 Recall |
| actual Recall@10 | evaluation 필터·무필터의 정답 있는 검색만 합친 동점 인정 Recall |
| latency | `VectorStore.search()` 호출 p50/p95/p99 |
| QPS | evaluation 성공 검색 수 / 전체 검색 작업 경과시간. Recall 후처리 제외 |
| CPU / RAM | 대상 컨테이너 합계 Avg/Max |
| index ready | drop/create부터 load·flush/refresh·index ready까지 |
| index size | pgvector 인덱스 관계 크기 또는 OpenSearch index store 크기; 미지원 -1 |

임베딩 생성, LLM 생성, reranking, hybrid search, 다중 노드 성능은 주 측정에 포함하지 않습니다.

## Query 흐름

```text
고정 document/query embedding
  ├─ exact cosine Top-10 ground truth
  └─ query-type 층화 SHA-256 분할
       ├─ calibration 100 → 추가 진단
       └─ evaluation 200 → warm-up + Recall/latency/QPS
```

evaluation은 200 query × measurement 5 = 1,000 search 단위이며 최소 30초와 유효 자원 표본 30개를 확보할 때까지 완전한 단위를 반복합니다. 기본 연장 기준은 180초이며 마지막 batch가 이를 넘을 수 있습니다. calibration과 evaluation ID hash를 결과 환경 정보에 저장합니다. 전체 grid를 본 뒤 고른 설정은 독립 holdout 검증과 구분합니다.

## 실행 흐름

`run-all-benchmarks.ps1`은 [14개 구성의 파라미터 sweep 행렬](../../data/benchmark-matrix.json)을 인덱스 조합별로 묶고 매 회차 전체를 재구축합니다. 3~5회 중 기본 5회입니다. DB 순서와 검색 파라미터 순서를 교차합니다. 동일 파라미터·동일 부하의 반복만 묶어 평균·median·p95/p99·분산을 기록합니다. 최신 다축 산포도는 모든 회차를 보존하고 큰 요약점에는 Recall 평균/나머지 지표 중앙값을 사용합니다. 0.80·0.90·0.95는 품질 참고선입니다.

1k 규모·실제 프로젝트 입력·1%/10%/50% 필터 선택도·독립 holdout은 이번 620개 결과에 포함되지 않습니다. 프로젝트 범위에 맞는 실제 입력 검증을 우선하며 100k/1M은 향후 규모 확장 시 선택적 실험입니다. 워밍업 미달 170건과 Milvus DISKANN 변동 5건은 경고로 표시하고 보존합니다.
