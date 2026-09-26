# Dataset와 Query Set

## 개요

한국어 합성 기술문서 코퍼스입니다. 재현 가능한 DB 비교가 목적이며 실제 서비스 데이터가 아닙니다.

프로젝트의 예상 운영 규모는 **1,000~10,000청크**입니다. [현재 완료한 fairness-v2](../07-results/fairness-v2-results-20260913.md)는 합성 10k·1024차원·concurrency 10 실험이며, 실제 1k 입력과 실서비스 분포·독립 holdout은 아직 측정하지 않았습니다.

| 파일 | 내용 |
|---|---|
| `data/documents_10000.jsonl` | 10,000 chunk |
| `data/queries_300.jsonl` | 300 query |
| `data/qrels.tsv` | query별 의미상 관련 chunk label |
| `data/query_distribution.csv` | query 유형별 개수/비율 |
| `data/manifest.json` | 실험 고정 조건 |
| `data/embeddings/` | 생성된 벡터와 manifest (gitignore) |
| `data/sample/` | 4차원 20건 smoke test용 |

## Corpus

- 10,000건, 전부 한국어
- 200개 topic
- 필드: `id`, `document_id`, `chunk_id`, `content`, `category`, `subcategory`, `language`,
  `tenant_id`, `status`, `access_level`, `chunk_profile`, `tags`, `topic_key`, `title`

`id` / `documentId` / `chunkId` / `content` / `embedding`을 제외한 나머지 필드가
전부 `metadata` 객체로 들어갑니다. 필터는 이 하위 키를 대상으로 합니다.

### Chunking 재현 조건

이 데이터셋은 애플리케이션이 원문을 다시 자르는 방식이 아니라 **사전에 생성된 합성 chunk
10,000건**을 입력으로 사용합니다. 따라서 단일 chunking 알고리즘, 고정 chunk size,
overlap은 해당 없음입니다. 대신 각 레코드의 `chunk_profile`
(`short` / `medium` / `long`)과 최종 건수를 `data/manifest.json`에 고정합니다.
실서비스 문서를 재청킹해 비교할 때에는 별도 manifest에 알고리즘·크기·overlap·최종 건수를
반드시 기록해야 합니다.

## Query Set

| 유형 | 개수 | 비율 |
|---|---:|---:|
| short_clear | 60 | 20% |
| long_natural | 60 | 20% |
| technical_term | 45 | 15% |
| semantic_paraphrase | 45 | 15% |
| exact_identifier | 30 | 10% |
| ambiguous | 30 | 10% |
| **metadata_filter** | **30** | **10%** |

영어 또는 혼용 질의 21건을 포함해 BGE-M3의 multilingual 특성을 일부 반영합니다.

## 필터 분포 — 결과 해석에 직접 영향

**300개 중 `metadata_filter` 30개(정확히 10%)만 필터를 가집니다.**

```text
{"tenant_id":"beta","status":"active"}    12건
{"tenant_id":"alpha","status":"active"}   10건
{"tenant_id":"delta","status":"active"}    7건
{"tenant_id":"gamma","status":"active"}    1건
나머지 270건                                필터 없음
```

위 표는 원본 300개 전체의 분포입니다. 이번 측정에 쓰는 evaluation 200개는 **무필터 180개 + 필터 20개**입니다. 필터 20개 중 정답이 빈 6개는 Recall 평균에서 제외하므로 1회 질의 집합 실행당 scored query는 194개입니다. latency·QPS는 빈 정답 질의까지 200개를 모두 포함합니다.

주 산포도의 전체 p95는 이 고정된 혼합 workload의 지연입니다. 필터 검색이 느리면 혼합 p95에 크게 영향을 줄 수 있으므로 원인 분석에서는 두 집단을 따로 확인합니다. 혼합 p95만으로 필터 비용이나 특정 검색 경로를 확정할 수는 없습니다.

```text
filtered_p50_ms   filtered_p95_ms   filtered_recall
unfiltered_p50_ms unfiltered_p95_ms unfiltered_recall
```

자세한 읽는 법은 [metrics.md](metrics.md)의 "전체 지연과 필터별 지연" 절에 있습니다.

## 데이터 규모의 한계

10,000건은 HNSW의 특성을 관찰하기에 **작은 편**입니다.

- float32 원시 벡터만 약 39 MiB입니다. 실제 메모리에는 인덱스·메타데이터·엔진 비용이 더해지며, 이 규모로 디스크 인덱스의 운영 규모 특성을 일반화할 수 없습니다.
- 검색 폭을 늘려도 품질이 거의 변하지 않는 구간이 있을 수 있습니다. 최소 파라미터부터 참고선보다 높은 Recall을 보이더라도 모든 점을 기록합니다.
- Qdrant에는 작은 데이터의 인덱스 생성을 유도하는 낮은 임계값을 적용하고 준비 상태를 확인합니다. 자세한 조건은 [Qdrant 구현](../05-databases/qdrant.md)에 있습니다.

선정(pgvector)은 이 합성 데이터 결과와 운영 편의성으로 내렸습니다. 실제 FAQ 데이터로 확인할 항목(HNSW 설정, Top-K, 임계값)은 [선정 결론의 남은 확인](../07-results/decision.md#남은-확인)에 있습니다. 100k/1M은 향후 데이터 성장 시 선택할 수 있는 축입니다. 합성 10k 결과만으로 1k 성능이나 서비스 품질까지 검증했다고 표현하지 않습니다.

현재 10,000개 문서 벡터에는 동일 값의 벡터가 포함되어 있습니다. v2는 K위 경계 동점을 인정하는 Recall과 strict-ID Recall을 함께 저장하므로 과거 strict-ID 기반 실험과 채점 조건을 구분합니다. [정답·동점 처리](ground-truth.md)를 참고합니다.

## 두 가지 평가의 분리

### 1. ANN 성능 비교 (이 저장소의 주 목적)

exact Top-K를 Ground Truth로 두고 ANN Top-K와 비교합니다.
embedding 품질과 무관하게 "DB가 exact를 얼마나 재현하는가"만 봅니다.

### 2. 의미 검색 품질 확인 (선택)

`data/qrels.tsv`로 실제 target topic 문서가 검색됐는지 평가합니다.
이 지표는 Vector DB뿐 아니라 embedding·chunking 품질의 영향을 함께 받으므로
DB 비교에 섞으면 안 됩니다.

## 벡터 생성

```powershell
.\gradlew.bat generateEmbeddings
```

[../02-concepts/chunk-and-embedding.md](../02-concepts/chunk-and-embedding.md)를 봅니다.

## 관련 문서

- [ground-truth.md](ground-truth.md)
- [metrics.md](metrics.md)
- [limitations.md](limitations.md)
