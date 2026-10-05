# pgvector

PostgreSQL 확장입니다. 현재 matrix는 PostgreSQL engine의 HNSW(T01)와 IVFFlat(T03)을 측정합니다.

**UBot이 채택한 방식입니다.** 결정 근거는 [선정 결론](../07-results/decision.md)에 있습니다.

## UBot-BE의 현재 사용 방식

[UBot-BE](https://github.com/ureca-UBot/UBot-BE)는 벤치마크 하네스와 설정이 다릅니다. 아래 벤치마크 수치를 서비스 설정의 성능으로 그대로 읽지 않습니다.

| 항목 | 벤치마크 하네스 | UBot-BE |
|---|---|---|
| 테이블 | 전용 `vector_documents` | `faq.vector vector(1024)`, 이력은 `old_faq.vector` |
| 인덱스 | HNSW `m=16`, `ef_construction=128` 또는 IVFFlat `lists=10` | HNSW `vector_cosine_ops`, 파라미터 미지정 → 기본 `m=16`, `ef_construction=64` (Flyway `V3`) |
| 검색 폭 | 요청마다 `set_config`로 `hnsw.ef_search` 지정 | 지정하지 않음 → 기본 `hnsw.ef_search=40` |
| 인덱스 강제 | `enable_seqscan=off` | 플래너 기본값 |
| 조건 | `metadata -> ? = ?::jsonb` | `deleted_at IS NULL` |
| 결과 수 | Top-10 | Top-3 (`ChatService` 임시 상수) |
| 접근 | JdbcTemplate + `PGvector` | JdbcTemplate + `PGvector` (`FaqVectorRepository`) |

HNSW는 인덱스로 `ef_search`개 후보를 찾은 뒤 `WHERE` 조건을 적용합니다. 삭제된 FAQ가 후보를 많이 차지하면 요청한 개수보다 적게 반환될 수 있습니다. 이때는 pgvector 0.8의 `hnsw.iterative_scan`을 검토합니다.

## 설정과 수명주기

`vector.pgvector.index-type`은 `hnsw` 또는 `ivfflat`입니다.

- HNSW: `m=16`, `ef_construction=128`; 검색 `hnsw.ef_search`
- IVFFlat: `lists=10`; 검색 `ivfflat.probes`

IVFFlat은 `CREATE INDEX` 시점의 데이터로 centroid를 학습합니다. `rebuild()`는 테이블만 만듭니다. 전체 벡터를 적재한 뒤 `awaitReady()`가 실제 행 수를 확인하고 IVFFlat 인덱스를 생성합니다. 인덱스 생성이 끝나야 검색 파라미터 sweep을 시작하며 이 생성 시간은 `time_to_index_ready_ms`에 포함됩니다. HNSW는 기존처럼 빈 테이블에 인덱스를 만든 뒤 벡터를 적재합니다.

작은 10k 테이블에서 planner의 exact sequential scan이 끼지 않도록 기본 benchmark는 `enable_seqscan=off`를 검색 세션에 적용합니다.

검색 timer에는 JDBC parameter 설정과 SQL 실행·row mapping이 포함됩니다.

## 필터

metadata는 JSONB이고 `metadata -> ? = ?::jsonb`로 필터해 문자열·숫자·boolean 타입을 보존합니다. 기본 하네스는 metadata용 별도 B-tree/GIN을 만들지 않으므로 `filtered_*` 결과를 반드시 따로 봅니다. 운영 설계를 검증할 때는 pgvector iterative scan과 실제 metadata index 전략을 별도 case로 추가해야 합니다.

## 크기

실제 생성한 HNSW 또는 IVFFlat 인덱스 이름을 사용해 `pg_relation_size`를 기록합니다.

## 최신 fairness-v2 실측 — 2026-09-13 완료

[최신 620개 결과](../07-results/fairness-v2-results-20260913.md) 중 이 DB의 실측입니다. 범위는 **모든 검색 파라미터와 다섯 재구축 회차**의 혼합 지표입니다. 최소 p95와 최대 Recall이 같은 점이라는 뜻은 아닙니다. 합성 10k·1024차원·동시성 10 조건이며 같은 Recall 수준의 설정을 6개 산포도로 비교합니다.

| 구성 | 실제 검색 그리드 | 점 수 | Recall@10 범위 | 전체 p95 ms 범위 |
|---|---|---:|---:|---:|
| T01 / PostgreSQL / hnsw | ef_search: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 45 | 0.668041–1.000000 | 3.809–57.979 |
| T03 / PostgreSQL / ivfflat | probes: 1, 2, 3, 4, 5, 6, 8, 10 | 40 | 0.827835–1.000000 | 9.422–84.193 |

85개 중 워밍업 미달 7개를 포함해 모두 보존합니다. 요청마다 `set_config`와 벡터 검색을 별도 SQL로 보내는 비용이 포함되므로 DB 엔진 자체의 시간으로 단정하지 않습니다.

HNSW 검색 폭별 5회 요약입니다(Recall 평균, 나머지 중앙값). 관측 peak RAM은 모든 설정에서 약 0.22GiB였습니다.

| ef_search | Recall | p50 ms | p95 ms | QPS |
|---:|---:|---:|---:|---:|
| 40 | 0.864 | 2.63 | 4.18 | 3,519 |
| 80 | 0.918 | 2.82 | 4.38 | 3,297 |
| 120 | 0.934 | 3.06 | 5.13 | 3,002 |
| 200 | 0.962 | 3.35 | 7.34 | 2,454 |
| 400 | 0.991 | 4.18 | 32.22 | 1,601 |

전체 설정은 [수치 부록](../07-results/assets/fairness-v2-20260912-1826/parameter-statistics.md)에 있습니다. [2026-09-11 v1 결과](../07-results/sweep-results-20260911.md)는 역사 자료입니다.

## 참고

- [pgvector 공식 문서](https://github.com/pgvector/pgvector)
