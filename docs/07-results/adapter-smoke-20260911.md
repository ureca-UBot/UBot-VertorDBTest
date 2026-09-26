# Adapter Lifecycle Smoke — 2026-09-11

> **현재 기준 안내 (2026-09-13):** 최신 결과는 [fairness-v2 620개 측정·6축 산포도](fairness-v2-results-20260913.md)다. 아래는 2026-09-11 당시 실행·해석을 보존한 역사 자료이며 수치와 판정 표현을 최신 결과에 적용하지 않는다. 채점·측정·어댑터 조건이 달라 최신 620개와 합산하지 않는다.

이 결과는 고위험 adapter가 실제 Docker 서버에서 3회 전체 재구축되는지 검증한 자료입니다.
DB 전체 순서를 교차한 T01~T28 공식 순위표가 아니므로 DB 간 우열 결론에 사용하지 않습니다.

| Test | DB / Engine / Index | Recall 평균 (min~max) | median p95 ms | 판정 |
|---|---|---:|---:|---|
| T03 | pgvector / PostgreSQL / IVFFlat | 0.891296 (0.881111~0.899444) | 70.4439 | 0.90 근접, 변동 있음 |
| T04 | pgvector / PostgreSQL / IVFFlat | 0.959444 (0.954444~0.962222) | 74.7877 | 목표 주변 |
| T09 | Weaviate / Native / HFresh | 0.958889 (0.953889~0.962778) | 36.5200 | 0.90 closest |
| T10 | Weaviate / Native / HFresh | 0.962593 (0.961667~0.963333) | 34.0812 | 0.95 상단 주변 |
| T25 | OpenSearch / Faiss / IVF | 0.912593 (0.912222~0.912778) | 9.0043 | 0.90 상단 밖 closest |
| T26 | OpenSearch / Faiss / IVF | 0.955926 (0.955556~0.956111) | 9.7806 | 목표 충족 |
| T27 | OpenSearch / JVector / DiskANN | 0.892963 (0.843333~0.937778) | 60.7780 | 불안정, 목표 미보장 |
| T28 | OpenSearch / JVector / DiskANN | 0.926296 (0.897778~0.983333) | 57.0533 | 불안정, 목표 미보장 |

## Milvus drift 원인과 수정 검증

수정 전에는 REST index state가 Finished이고 load progress가 100%여도 진단 직전

```text
stateBefore.querySegments = []
```

인 경우가 있었습니다. calibration은 아직 query node에 segment가 보이지 않는 전환 경로를 측정했고,
본 측정 뒤에는 10,000-row Sealed segment가 나타나 같은 파라미터의 Recall이 크게 달라졌습니다.

`awaitReady()`를 다음 조건 모두가 참일 때만 반환하도록 수정했습니다.

- indexState=Finished, indexedRows=10,000, pendingRows=0
- LoadStateLoaded, loadProgress=100
- getQuerySegmentInfo에 index name을 가진 Sealed/Flushed segment가 존재
- query segment row 합계가 기대 건수 이상

수정 후 3회 결과는 다음과 같습니다.

| Test | Index | Search | Calibration | Evaluation 평균/range | Same-index serial/concurrent | Stability |
|---|---|---|---:|---:|---|---|
| T12 | HNSW | ef=80 | 0.963333 | 0.952222 / 0 | 3회씩 모두 0.963333 | 통과 |
| T13 | IVF_FLAT | nprobe=4 | 0.915556 | 0.912222 / 0 | 3회씩 모두 0.915556 | 통과 |
| T15 | IVF_SQ8 | nprobe=4 | 0.911111 부근 | 0.911111 / 0 | drift 없음 | 통과, 목표 상단 밖 |
| T17 | IVF_PQ | nprobe=128 | 0.59 부근 | 0.601111 / 0 | drift 없음 | 안정적이나 품질 탈락 |

과거 HNSW 0.9578→0.7422 drift는 query-segment readiness 누락으로 설명되고 수정 후 재현되지 않았습니다.
다만 Milvus 전체 T11~T20 공식 실행이 완료되기 전에는 제품 순위표에 복귀시키지 않습니다.

## 남은 공식 검증

- T01~T28 전체를 같은 코드 버전과 DB 교차 순서로 3~5회 실행
- 1%/10%/50% 필터 선택도
- 실제 100k 이상 입력
- 실제 프로젝트 non-synthetic workload
