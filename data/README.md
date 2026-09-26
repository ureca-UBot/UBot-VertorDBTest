# BGE-M3 Vector DB Benchmark Dataset

이 데이터셋은 pgvector / Qdrant / Weaviate / Milvus / OpenSearch를 동일 조건에서 비교하기 위한 합성 기술문서 코퍼스다.

실제 프로젝트의 예상 범위는 1,000~10,000개 청크다. 현재 완료 결과는 이 합성 데이터 10,000개만 사용했으며, 1k 규모와 실제 프로젝트 분포의 검증을 대신하지 않는다.

## 파일

- `documents_10000.jsonl`: 10,000개 chunk record
- `queries_300.jsonl`: 300개 고정 query
- `qrels.tsv`: query별 의미상 관련 chunk label
- `query_distribution.csv`: query 유형별 개수/비율
- `manifest.json`: benchmark 고정 조건

## BGE-M3 적용

원본 데이터셋에는 embedding vector 자체가 포함되어 있지 않다.
현재 프로젝트에서는 로컬 Ollama의 `bge-m3:latest`로 `documents_10000.jsonl.content`와
`queries_300.jsonl.text`를 한 번만 embedding하여 다음 파일을 생성했다.

- `embeddings/document-vectors.jsonl`
- `embeddings/query-vectors.jsonl`
- `embeddings/embedding-manifest.json`
- `queries/queries.jsonl`

동일한 vector 파일을 모든 Vector DB에 재사용한다. 생성·검증 명령은 `./gradlew generateEmbeddings`이며,
완성된 출력의 provenance가 검증되면 다시 생성하지 않고 재사용한다. manifest의 model digest와 SHA-256이 실험 데이터 동일성의 기준이다.

DB benchmark에서는 embedding 생성 시간을 latency에 포함하지 않는다.

## Chunking 조건

입력 코퍼스는 벤치마크 전에 이미 10,000개의 합성 chunk record로 만들어져 있다.
따라서 이 저장소 안에서 실행되는 단일 chunking 알고리즘·고정 크기·overlap 값은 없으며
`manifest.json`에 각각 해당 없음(`null`)으로 기록한다. 실제 입력의 `chunk_profile`
(`short` / `medium` / `long`)과 최종 chunk 수 10,000건이 재현 조건이다.

## Query 유형

- short_clear
- long_natural
- technical_term
- semantic_paraphrase
- exact_identifier
- ambiguous
- metadata_filter

일부 영어 query를 포함해 BGE-M3의 multilingual 특성을 일부 반영했다.

## 평가를 두 가지로 분리

### 1. ANN 성능 비교
각 query vector에 대해 Exact Top-K를 ground truth로 생성하고 ANN Top-K와 비교한다.

현재 `fairness-sweep-v2`의 주 Recall은 정확 검색의 K위 경계 동점을 인정한다. K위보다 좋은 ID 집합 A, K위 동점 전체 B, 반환 ID 집합 R에 대해 `(R∩A 수 + min(K-A 수, R∩B 수))/K`다. K는 `min(10, eligible 문서 수)`이고 기본 동점 허용 오차는 0이다. 기존 ID 교집합 공식은 `filtered.strictIdRecall`·`unfiltered.strictIdRecall`로 별도 보존한다.

정답이 빈 질의는 Recall 평균에서 제외하고 빈 결과 반환 여부를 별도 기록한다.

현재 14개 구성의 검색 파라미터 그리드를 전부 측정한다. 최신 다축 산포도의 Recall@10 0.80·0.90·0.95는 참고선이며 품질값으로 결과를 제거하지 않는다. 300개 query의 기존 분할은 유지하며 evaluation 200개로 모든 파라미터를 측정한다. calibration 100개는 추가 진단용이다. 이번 evaluation은 무필터 180개·필터 20개이며 정답이 없는 필터 6개를 뺀 194개가 Recall 평균 대상이다. QPS와 latency에는 빈 정답 질의도 포함된다. 전체 그리드를 본 뒤 고른 설정은 별도 holdout 검증을 마친 것이 아니다.

이 입력으로 [최신 620개 v2 측정](../docs/07-results/fairness-v2-results-20260913.md)을 완료했다. 입력 해시와 결과 근거는 해당 보고서에서 확인한다. [과거 372개 v1 sweep](../docs/07-results/sweep-results-20260911.md)은 다른 채점·측정 조건의 역사 자료이며 최신 결과와 합산하지 않는다.

`workloads/filter-selectivity/`는 `generateFilterSelectivityWorkloads`가 만드는 파생 입력이다.
embedding은 바꾸지 않고 metadata만 1%/10%/50% cohort로 확장한다.

### 2. 의미 검색 품질 확인 (선택)
`qrels.tsv`를 사용해 실제 target topic 문서가 검색됐는지 별도로 평가할 수 있다.
이 지표는 Vector DB 자체뿐 아니라 embedding/chunking 품질의 영향을 함께 받는다.

## 주의

이 데이터는 재현 가능한 DB 비교용 합성 데이터다.
DB 선정(pgvector)은 이 데이터의 측정 결과와 운영 편의성으로 내렸다. 실제 FAQ/질의 분포로 다시 측정하려면 `scripts/run-real-workload-validation.ps1`을 쓴다.
`run-real-workload-validation.ps1`은 문서 또는 query에서 `synthetic:true`를 발견하면 실행을 거부한다.
