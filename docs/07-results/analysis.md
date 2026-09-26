# Analysis

> **현재 기준 안내 (2026-09-13):** 최신 결과는 [fairness-v2 620개 측정·6축 산포도](fairness-v2-results-20260913.md)다. 아래는 2026-09-11 당시 실행·해석을 보존한 역사 자료이며 수치와 판정 표현을 최신 결과에 적용하지 않는다. 채점·측정·어댑터 조건이 달라 최신 620개와 합산하지 않는다.

> **역사적 결함 분석:** 아래 수치는 새 T01~T28 프로토콜의 최종 결과가 아닙니다.
> 지적된 query 누수·단일 재구축·QPS 분모·Milvus drift 문제는 현재 코드에서 별도 계약으로 다룹니다.

1차 본실험(2026-09-11) 결과를 검토하고 발견한 측정 결함, 그 근거와 수정 내용입니다.
결함 수정 후 재실행은 완료했으며 결과는
[benchmark-results-rerun.md](benchmark-results-rerun.md)에 분리해 기록했습니다.

## 요약

| 결함 | 영향 | 상태 |
|---|---|---|
| Qdrant payload index 누락 | 필터 질의가 full scan. 판정 1번이 뒤집힘 | 수정 완료 |
| 필터/무필터 질의 혼재 | 합산 p95·p99가 ANN 꼬리가 아님 | 수정 완료 |
| Recall과 latency의 비교 모집단 불일치 | 전체 Recall과 무필터 p95를 한 점으로 표시 | 수정 완료 |
| 낮은 목표 Recall 도달 불가 | 0.80·0.90은 엄격 조건 통과 제품이 하나뿐 | **미해결** |
| CPU·메모리 비교 가능성 | 절반 이상의 행이 사용 불가 | 문서화만 |

1차 실행의 원본 수치는 [benchmark-results.md](benchmark-results.md)에 기록으로 남겨두었습니다.

---

## 결함 1 — Qdrant payload index 누락

### 증상

Qdrant의 p95가 다른 DB보다 4~5배 높았습니다.

```text
Qdrant    0.80  p50 2.48 ms   p95 130.74 ms
Weaviate  0.80  p50 12.53 ms  p95 29.73 ms
```

이 수치로 "Weaviate가 Qdrant보다 빠르다"고 판정했습니다.

### 근거

`hnsw_ef`를 12.5배 올려도 p95가 움직이지 않았습니다.

| hnsw_ef | p50 | p95 |
|---:|---:|---:|
| 80 | 2.48 ms | 130.74 ms |
| 400 | 3.78 ms | 104.25 ms |
| 1000 | 5.04 ms | 126.64 ms |

p50은 정상적으로 반응하는데 p95만 무반응입니다.
**탐색 파라미터에 반응하지 않는 경로는 HNSW를 타고 있지 않습니다.**

원인은 컬렉션 생성 코드에 payload index 생성이 없었던 것입니다.
Qdrant는 필터 대상 필드에 payload index가 없으면 filtered HNSW를 쓰지 못하고
전체 점수를 스캔합니다.

### 결론

판정 1번은 성립하지 않습니다. 무필터 질의만 보면 순서가 반대입니다.

```text
무필터 p50:  Qdrant 2.48 ms  <  Weaviate 12.53 ms
```

### 수정

```yaml
vector.qdrant.payload-index-fields:
  tenant_id: keyword
  status: keyword
  ...
```

컬렉션 생성 후 `metadata.<키>`에 payload index를 만들고,
`awaitReady()`가 `payload_schema`에 선언 필드가 모두 있는지 확인합니다.
없으면 측정을 시작하지 않고 실패합니다.

실제 Qdrant 1.19.0 컨테이너로 API 모양과 `payload_schema` 반영을 검증했습니다.

---

## 결함 2 — p95·p99가 ANN 꼬리지연이 아님

### 증상

질의 300개 중 `metadata_filter` 30개, 정확히 10%만 필터를 가집니다.
필터 질의가 더 느리므로 **이 30개가 통째로 지연 분포의 상위 10%를 차지합니다.**

즉 p95도 p99도 필터 질의 집단의 값입니다.

### 근거

필터 비율이 `r`일 때 `average ≈ (1-r)·p50 + r·p95`가 성립하면
percentile이 필터 집단에 지배당하고 있다는 뜻입니다. 15행 전부에서 성립했습니다.

| 행 | p50 | p95 | 예측 avg | 실측 avg |
|---|---:|---:|---:|---:|
| Qdrant 0.80 | 2.48 | 130.74 | 15.7 | **15.65** |
| Weaviate 0.80 | 12.53 | 29.73 | 14.3 | **14.31** |
| Milvus 0.95 | 5.79 | 26.88 | 7.9 | **7.85** |
| pgvector 0.95 | 5.04 | 34.07 | 8.0 | **7.46** |

두 번째 근거는 `ef` 무반응입니다. Weaviate도 `ef` 40→400에서
p50은 12.53→13.97로 오르는데 p95는 29.73→27.88로 움직이지 않습니다.

### 수정

`LatencyCollector`가 필터/무필터 계열을 따로 모으고,
결과에 `filtered_*`와 `unfiltered_*` 컬럼 12개를 추가했습니다.
SVG 차트의 x축도 합산 p95에서 unfiltered p95로 바꿨습니다.

`filtered_recall`과 `unfiltered_recall`을 함께 기록하므로
post-filtering으로 Top-K를 못 채우는 제품도 드러납니다.

---

## 결함 3 — Recall과 latency의 비교 모집단 불일치

첫 번째 분리 재실행은 x축을 무필터 p95로 바꿨지만 y축과 목표 판정은 필터 30개를 포함한
전체 `actualRecall`을 계속 사용했습니다. 서로 다른 모집단을 한 점으로 짝지었으므로
동일 Recall 비교가 엄밀하게 성립하지 않았습니다.

자동 튜닝과 `targetMet` 판정을 무필터 270개 질의로 통일하고 `comparisonRecall`을 추가했습니다.
전체 300개 질의의 `actualRecall`은 서비스 혼합 참고값으로 계속 보존합니다. SVG의 y축도
`comparisonRecall`을 사용합니다.

---

## 결함 4 — 낮은 목표 Recall 도달 불가 (미해결)

최종 재실행에서 `WITHIN_TOLERANCE` 선택은 7칸, 본 측정 `target_met=true`도 7칸이지만
두 조건을 동시에 만족한 유효 행은 6칸이었습니다.

최소 후보에서 이미 목표를 넘어버리는 DB가 있습니다.

```text
OpenSearch ef_search=10 → 비교 Recall 0.9296    ← 0.80, 0.90 도달 불가
Milvus     ef=40        → 비교 Recall 0.8959    ← 0.80 도달 불가
```

후보는 `candidate >= topK`로 걸러지므로 10 밑으로 내려갈 수 없습니다.
`ef`로 Recall을 낮출 수 없으면 `M` / `ef_construction`을 낮춰야 하는데
현재 두 값은 전 DB에서 16 / 128로 고정되어 튜닝 축에 없습니다.

데이터가 10,000건으로 작아 HNSW 그래프가 얕은 것이 근본 원인입니다.

결과적으로 목표 0.80과 0.90은 엄격 조건을 통과한 제품이 각각 Weaviate 하나뿐이라
DB 간 비교가 불가능합니다. 0.95에서만 4개 제품 비교가 가능합니다.

---

## 결함 5 — CPU·메모리 비교 가능성

- CPU가 0.01~0.7%로 기록된 5개 행은 `docker stats`의 약 1초 집계창보다
  측정 구간이 짧아 생긴 과소 집계입니다. 이 행들을 빼면 CPU 비교에 15행 중 11행만 남습니다.
- OpenSearch의 4,835 MiB는 `-Xms4g -Xmx4g` 선점 heap입니다. 사용량이 아니라 설정값입니다.

이 두 컬럼은 현재 구조에서 DB 간 직접 비교에 쓸 수 없습니다.
[../03-benchmark-design/limitations.md](../03-benchmark-design/limitations.md)에 명시했습니다.

---

## 재실행 검증 결과

2026-09-11 재실행에서 다음을 확인했습니다.

1. Qdrant payload index 6개가 생성됐고 `awaitReady()` 검증을 통과했다.
2. Qdrant 필터 p95는 5.44~7.30 ms로, 1차 실행의 full-scan 지연이 재현되지 않았다.
3. 모든 행이 필터 150회와 무필터 1,350회를 별도 기록했다.
4. `comparison_recall`과 무필터 p95가 같은 모집단을 사용하며 SVG 두 축에도 반영됐다.
5. `WITHIN_TOLERANCE` 7행과 본 측정 `target_met=true` 7행의 교집합은 6행이다.
   Milvus 0.90·0.95의 양방향 drift를 별도 상태로 드러냈다.
6. PostgreSQL에 원문 200건·청크 10,000건과 데이터 해시가 기록됐고 Flyway V1·V2가 적용됐다.
7. 애플리케이션 오류 로그 5개는 비어 있고, 결과 JSON·CSV의 분리 지표 누락도 없다.

재실행 전 확인 항목은 아래와 같았다.

1. Qdrant 무필터 p50/p95가 이전 2.48 / 130.74에서 어떻게 바뀌는가
2. `filtered_p95_ms`가 DB별로 어떻게 갈리는가 — 필터 처리 능력의 실제 비교
3. `filtered_recall` < `unfiltered_recall`인 DB가 있는가 — post-filtering 신호
4. `recall_selection`이 `WITHIN_TOLERANCE`인 행이 몇 개인가

```powershell
.\gradlew.bat bootJar
.\scripts\run-all-benchmarks.ps1 -ResultDirectory benchmark-result/rerun-01
```

## 배운 것

**조용한 성능 저하가 가장 위험합니다.**
Qdrant는 오류를 내지 않았습니다. 그냥 느렸고, 그 숫자가 그대로 표에 들어갔습니다.

그래서 설정 누락으로 제품이 느린 경로에 빠지면 측정 대신 실패하도록 바꿨습니다.
[../06-implementation/adapter-policy.md](../06-implementation/adapter-policy.md)의 8번 규칙입니다.

**percentile은 단일 워크로드에서만 의미가 있습니다.**
성격이 다른 질의를 한 분포에 섞으면 percentile은 두 집단의 경계를 가리킵니다.
워크로드를 나눠 측정하는 것이 유일한 해법입니다.

## 관련 문서

- [benchmark-results.md](benchmark-results.md)
- [decision.md](decision.md)
- [../03-benchmark-design/limitations.md](../03-benchmark-design/limitations.md)
