# Vector DB 선정 결론 — pgvector

2026-09-27 문서 반영. 근거 측정은 [fairness-v2 620개 결과](fairness-v2-results-20260913.md)다.

## 결론

UBot은 별도 Vector DB를 두지 않고 **PostgreSQL의 pgvector**를 사용한다.

같은 Recall 수준에서 Qdrant·OpenSearch가 pgvector보다 빨랐다. 다만 차이는 p50 기준 1~2ms, p95 기준 수 ms~수십 ms였다. 절대값으로는 크지 않다고 판단했고 이미 쓰는 PostgreSQL에 FAQ와 벡터를 함께 두는 편의성을 우선했다.

## 성능 차이는 어느 정도였나

합성 10k 청크·Top-10·동시성 10에서 같은 설정을 5회 재구축해 측정한 값이다. Recall은 5회 평균, 나머지는 5회 중앙값이다. 전체 124개 설정은 [수치 부록](assets/fairness-v2-20260912-1826/parameter-statistics.md)에 있다.

**Recall 약 0.96**

| 구성 / 검색 파라미터 | Recall | p50 ms | p95 ms | QPS | peak RAM GiB |
|---|---:|---:|---:|---:|---:|
| **pgvector HNSW / ef_search=200** | 0.962 | 3.35 | 7.34 | 2,454 | 0.21 |
| Qdrant HNSW / hnsw_ef=20 | 0.968 | 2.56 | 3.94 | 3,635 | 0.10 |
| OpenSearch Lucene HNSW / candidate_k=400 | 0.961 | 3.49 | 4.93 | 2,731 | 4.71 |
| Milvus HNSW / ef=80 | 0.971 | 4.85 | 17.72 | 1,618 | 0.66 |
| Weaviate HNSW / ef=400 | 0.962 | 13.04 | 24.40 | 715 | 0.18 |

**Recall 약 0.99**

| 구성 / 검색 파라미터 | Recall | p50 ms | p95 ms | QPS | peak RAM GiB |
|---|---:|---:|---:|---:|---:|
| **pgvector HNSW / ef_search=400** | 0.991 | 4.18 | 32.22 | 1,601 | 0.22 |
| Qdrant HNSW / hnsw_ef=40 | 0.986 | 2.69 | 4.19 | 3,460 | 0.10 |
| OpenSearch Faiss HNSW / ef_search=120 | 0.985 | 3.41 | 5.09 | 2,761 | 4.81 |
| Milvus IVF_FLAT / nprobe=8 | 0.986 | 5.00 | 20.09 | 1,510 | 0.67 |
| Weaviate HNSW / ef=800 | 0.985 | 14.45 | 26.73 | 652 | 0.18 |

읽는 법:

- pgvector는 0.96 수준에서 가장 빠른 구성보다 p95가 약 3ms, 0.99 수준에서 약 28ms 느렸다. p50 차이는 두 수준 모두 2ms 이내였다.
- pgvector 측정값에는 벤치마크 어댑터가 요청마다 보내는 `set_config` SQL 왕복이 포함돼 있다. pgvector에 불리한 쪽의 비용이다.
- 챗봇 응답에는 질문 임베딩 호출과 LLM 생성이 함께 들어간다. 이 벤치마크는 그 시간을 측정하지 않았다. 검색 단계의 차이가 전체 응답에서 작다는 판단은 팀의 판단이다.
- OpenSearch RAM에는 설정한 4GiB JVM 힙이 들어 있다. Milvus RAM은 본체·etcd·MinIO 합계다.
- 표의 일부 설정에는 워밍업 경고가 있다(수치 부록의 `경고` 열). 경고 회차도 5회 집계에 포함했다.

## 편의성으로 얻는 것

UBot-BE 코드 기준이다(`develop`, 2026-09-23 커밋 기준).

- **추가 서버가 없다.** UBot-BE는 이미 PostgreSQL 18 + PostGIS를 쓴다. pgvector는 같은 이미지([infra/postgres/Dockerfile](https://github.com/ureca-UBot/UBot-BE/blob/develop/infra/postgres/Dockerfile))의 확장이며 Flyway `V1`에서 켠다.
- **원문과 벡터가 같은 행에 있다.** `faq.vector`, `old_faq.vector` 컬럼에 저장한다. 별도 Vector DB와 원본 DB 사이의 동기화·정합성·재색인 관리가 없다.
- **조건을 같은 SQL에 쓴다.** 삭제된 FAQ 제외는 [FaqVectorRepository](https://github.com/ureca-UBot/UBot-BE/blob/develop/src/main/java/com/ubot/faq/repository/FaqVectorRepository.java)의 `WHERE deleted_at IS NULL` 한 줄이다.
- **스키마와 테스트가 하나로 관리된다.** 벡터 컬럼과 HNSW 인덱스는 Flyway `V3`에 있고 테스트는 Testcontainers로 같은 이미지를 띄운다.
- **자원이 작다.** 벤치마크에서 pgvector 컨테이너의 관측 peak RAM은 약 0.22GiB였다.

## UBot-BE 현재 설정과 벤치마크 조건의 차이

벤치마크의 pgvector 수치는 DB끼리 비교하려고 조건을 맞춘 값이다. UBot-BE의 현재 설정을 그대로 측정한 값이 아니다.

| 항목 | 벤치마크 | UBot-BE 현재 코드 |
|---|---|---|
| 인덱스 | HNSW `m=16`, `ef_construction=128` | HNSW, 파라미터 미지정 → pgvector 기본값 `m=16`, `ef_construction=64` (`V3`) |
| 검색 폭 | `ef_search` 10~1000 전체 측정 | 설정하지 않음 → 기본값 `hnsw.ef_search=40` |
| 인덱스 사용 | `enable_seqscan=off`로 강제 | 플래너가 결정. 데이터가 적으면 순차 스캔(정확 검색)을 고를 수 있다 |
| Top-K | 10 | 3 (`ChatService` 임시 상수) |
| 필터 | JSONB `tenant_id`·`status` 동등 조건 | `deleted_at IS NULL` |
| 임베딩 대상 | 합성 청크 본문 | FAQ 질문 (`saveVectorForFaq`) |
| 데이터 | 합성 10k | 실제 FAQ, 예상 1천~1만 |
| PostgreSQL / pgvector | 17 / 0.8.6 | 18 / 0.8.6 |

## 남은 확인

결정을 다시 여는 조건이 아니라 pgvector를 쓰면서 확인할 항목이다.

1. **Top-K와 임계값:** UBot-BE의 `TOP_K=3`, 임계값 `0.75`는 코드에 TODO로 남은 임시값이다. 실제 FAQ와 질문으로 정해야 하며 이 벤치마크가 대신 정해 주지 않는다.
2. **HNSW 설정:** 실제 FAQ에서 기본값(`ef_construction=64`, `ef_search=40`)의 Recall이 충분한지 확인한다. 필요하면 `ef_search`를 올린다. 위 표처럼 ef_search를 올리면 p95가 함께 늘어난다.
3. **삭제 행과 필터:** HNSW는 인덱스로 후보를 찾은 뒤 `WHERE`를 적용한다. 삭제된 FAQ가 탐색 후보를 많이 차지하면 3개보다 적게 반환될 수 있다. 그런 경우 pgvector 0.8의 `hnsw.iterative_scan`을 검토한다.
4. **규모가 크게 늘 때:** 10만 건 이상으로 커지면 [규모 검증 스크립트](../../scripts/run-shortlist-scale-validation.ps1)로 다시 측정한다. 현재 1천~1만 범위에서는 필요하지 않다.

## 벤치마크 결과를 읽는 기준

- 비슷한 **실제 Recall**의 설정끼리 지연·QPS·자원을 비교한다. 서로 다른 검색 파라미터를 평균 내어 가상의 한 구성으로 만들지 않는다.
- 무필터 Recall–무필터 p95와 필터 Recall–필터 p95를 분리한다. 혼합 QPS는 evaluation 200개 전체 부하이고 혼합 Recall은 빈 정답 6개를 제외한 194개에 대한 점수다.
- 실행 예산(DB 합계 4 vCPU/8GiB/swap 없음)과 선정 조건은 다르다. RAM은 관측 비교 지표다.
- 워밍업 경고 170/620행과 Milvus DISKANN Recall 변동 5행은 삭제하지 않고 표시했다. 선정에 쓴 pgvector·Qdrant·OpenSearch 비교와는 별개다.
- 코드의 [DecisionGate](../../src/main/java/com/myapp/benchmark/DecisionGate.java)는 임계값이 없으면 Recall 0.95·p95 30ms·RAM 2GiB를 기본 적용해 summary의 `decision.eligible`을 만든다. 합의된 기준이 아니므로 이번 선정에 쓰지 않았다. 코드는 그대로 남아 있다.

[지표 정의](../03-benchmark-design/metrics.md) · [측정 한계](../03-benchmark-design/limitations.md) · [pgvector 구현](../05-databases/pgvector.md)
