# Common Issues

증상 → 진단 → 해결 → 재검증 순서로 정리했습니다.

현재 실행 기준은 [fairness-v2](../03-benchmark-design/fairness-v2.md)이고 [최신 완료 결과](../07-results/fairness-v2-results-20260913.md)는 합성 10k·1024차원·동시성 10의 620점입니다. 아래 과거 사례는 당시 조건으로만 읽습니다. 저장된 결과를 확인하려고 DB를 다시 실행하거나 기존 결과 디렉터리를 덮어쓸 필요는 없습니다.

---

## No vector store is active

```text
IllegalStateException: No vector store is active. Enable a vector DB Spring profile.
```

기본 `application.yml`은 `vector.store.type: none`이라 어떤 어댑터도 활성화되지 않습니다.

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=qdrant"
```

### 확인

```powershell
Invoke-RestMethod http://localhost:8080/api/search/store
# expected: database = qdrant
```

---

## Vector count mismatch

```text
IllegalStateException: Vector count mismatch: expected 10000 but store has 9873
```

적재가 일부 실패했습니다. 이 상태로 측정하면 Recall 저하로 오인하게 되므로 즉시 중단합니다.

### 진단

```powershell
docker compose logs --tail=100 qdrant
Get-Content benchmark-result/<dir>/logs/<profile>-application-error.log -Tail 50
```

흔한 원인: 메모리 상한 도달, bulk 요청 실패, 컨테이너 재시작.

### 해결

`rebuildAndLoad: true`로 다시 실행합니다. 반복되면 `benchmark.upsert-batch-size`를 낮춥니다.

```powershell
$env:UPSERT_BATCH_SIZE = "128"
```

---

## Qdrant payload index is missing

```text
IllegalStateException: Qdrant payload index is missing for [metadata.tenant_id];
filtered search would fall back to a full scan
```

선언한 payload index가 없어 실행 조건을 충족하지 못한 상태입니다. 준비 조건 오류와 실제 측정에서 낮은 Recall이 나온 경우를 구분합니다.

### 진단

```powershell
Invoke-RestMethod http://localhost:6333/collections/benchmark_chunks |
  Select-Object -ExpandProperty result | Select-Object payload_schema
```

### 해결

`application-qdrant.yml`의 `payload-index-fields`에 필터 키가 선언돼 있는지 확인하고
컬렉션을 다시 만듭니다(`rebuildAndLoad: true`).

자세한 배경은 [../05-databases/qdrant.md](../05-databases/qdrant.md)를 봅니다.

---

## PostgreSQL source snapshot does not match

```text
IllegalStateException: PostgreSQL source snapshot does not match the benchmark input;
run with rebuildAndLoad=true
```

`documents`, `document_chunks`, `benchmark_dataset_state`의 해시 또는 건수가 현재 vector
JSONL과 다릅니다. 변경된 원본을 검색 DB에 조용히 재사용하지 않도록 막은 것입니다.

### 해결

입력을 의도적으로 교체한 것이 맞는지 먼저 확인한 뒤 `rebuildAndLoad: true`로 실행합니다.
동기화가 끝나면 PostgreSQL에는 원문 200건과 chunk 10,000건이 있어야 합니다.

---

## Flyway가 non-empty schema에서 시작하지 못한다

```text
Found non-empty schema(s) "public" but no schema history table
```

이 프로젝트는 `spring.flyway.baseline-version=0`과 `baseline-on-migrate=true`로 기존 개발
볼륨을 인수합니다. 해당 설정을 지웠거나 프로필에서 덮어쓰지 않았는지 확인합니다.
baseline을 1로 올리면 기존 볼륨에서 V1 Source of Truth 테이블 생성을 건너뛸 수 있으므로
임의로 바꾸지 않습니다.

---

## Resource budget mismatch

```text
Resource budget mismatch for qdrant: expected cpuNano=4000000000,... actual cpuNano=0,...
```

`docker inspect`가 보고한 실제 상한이 선언과 다릅니다. 측정을 시작하지 않습니다.

### 진단

```powershell
docker inspect vector-qdrant --format `
  'cpuNano={{.HostConfig.NanoCpus}},mem={{.HostConfig.Memory}},swap={{.HostConfig.MemorySwap}}'
```

### 해결

컨테이너가 예전 설정으로 떠 있는 경우가 대부분입니다. 다시 만듭니다.

```powershell
docker compose --profile qdrant up -d --force-recreate qdrant
```

스크립트를 거치지 않고 수동으로 `docker compose up`을 했다면
`VECTOR_CPU_LIMIT` / `VECTOR_MEMORY_LIMIT` 환경변수가 설정되지 않아 기본값이 적용됩니다.

---

## 자원 값이 전부 -1

현재 CSV의 `cpu_average_percent`, `cpu_max_percent`, `ram_average_bytes`, `ram_max_bytes`가 `-1`이고 `resource_samples=0`이면 유효한 검색 구간 내부 표본이 없다는 뜻입니다. 수집 실패뿐 아니라 표본 수집 시간이 검색 구간을 벗어났을 수도 있습니다. 원시 -1을 지우거나 0으로 바꾸지 않습니다.

### 진단

```powershell
docker stats --no-stream --format "{{.CPUPerc}}|{{.MemUsage}}|{{.BlockIO}}" vector-qdrant
```

### 해결

- Docker 소켓 권한 확인
- 프로필 YAML의 `benchmark.container-names`가 실제 컨테이너 이름과 일치하는지 확인
- `measurement_time_ms`와 `resource_samples` 확인; 현재 기본 최소 30초와 자원 표본 30개를 모두 확보

```yaml
benchmark.container-names: [vector-qdrant]
```

---

## CPU가 0.01% 같은 비현실적인 값

짧은 검색이 끝난 뒤 유휴 CPU 표본을 합치면 부하 중 사용량이 과소 집계될 수 있습니다. 실제로 최초 sweep 시도 `sweep-20260911-215805`의 26개 기록에서 계측 문제를 발견했습니다. 해당 기록과 중단 사유는 별도로 보존했습니다.

현재 구현은 1,000요청 단위를 최소 30초·자원 표본 30개를 모두 충족하도록 반복하고, 수집 시작·종료가 모두 검색 구간 안인 표본만 평균·최대에 사용합니다. 종료 직후 유휴 표본을 추가하지 않습니다. 실행 스크립트의 `-MinimumMeasurementTimeMs 30000 -MinimumResourceSamples 30` 또는 애플리케이션의 `benchmark.minimum-measurement-time-ms`·`benchmark.minimum-resource-samples`를 확인합니다. 30표본을 얻기 위해 실제 시간은 30초보다 길어질 수 있습니다.

[과거 v1의 372개 결과](../07-results/sweep-results-20260911.md)는 검색 구간 최소 5,001ms·자원 표본 최소 2개·CPU/RAM 누락 0개였습니다. 최신 v2 620점은 검색 구간 최소 **60,210ms**, 자원 표본 최소 **30개**, 자원 증거 불완전 0개입니다. 이 둘의 조건을 합치지 않습니다. 숫자가 작다는 이유만으로 버리지 않고 수집 시점·실제 부하·표본 수를 함께 확인합니다.

---

## Recall이 0.90 또는 0.95를 넘지 않는다

검색 파라미터의 실제 품질 관측입니다. Recall만으로 실행을 실패 처리하거나 구성·측정값을 제거하지 않습니다. 최신 v2의 Milvus IVF_PQ **45점**도 혼합 Recall **0.494330–0.613918** 범위 그대로 보존했습니다. 과거 v1의 27점·0.492268–0.611856은 별도 역사 수치입니다. 전체 그리드와 산포도에서 비슷한 실제 Recall의 비용·반복 범위를 함께 해석합니다.

---

## summary에서 모든 구성이 eligible=false다

현재 `DecisionGate`는 holdout 미실시·워밍업 경고·성능 조건을 하나의 판정에 합치고, 별도 임계값이 없으면 Recall 0.95·p95 30ms·RAM 2GiB를 적용합니다. 최신 실행은 탐색형이므로 모두 최종 독립 검증 조건을 충족하지 않은 상태이며, **30ms·2GiB는 합의된 서비스 기준도 아닙니다.** 이를 DB 장애나 전체 측정 무효로 해석하지 않습니다.

코드와 기존 summary는 그대로 보존합니다. 현재 산포도 비교에서는 해당 자동 판정을 선정 근거로 쓰지 않고 측정 무결성·품질 경고·미실시 검증을 나눠 읽습니다. DB 합계 4 vCPU/8GiB와 OpenSearch의 4GiB 힙은 실행 조건이며 RAM Avg/Max는 관측 비교 지표입니다.

---

## 워밍업 경고 또는 Milvus verified=true인데 측정 중 Recall이 변한다

최신 620점 중 워밍업 경고는 170점입니다. 200질의 pass에서 최근 3회 p95 변동 폭을 검사한 결과이며, 경고가 약 60초 본 측정 전체의 무효를 뜻하지는 않습니다. 반대로 통과도 본 측정 안정성을 보증하지 않습니다.

Milvus의 별도 진단은 본 측정 뒤 수행하므로 측정 중 변동을 놓칠 수 있습니다. DISKANN 5점에서는 같은 질의의 Recall 변동이 확인됐는데도 `verified=true`였습니다. query audit·검색 설정·index/segment 상태를 함께 확인하고 경고를 보존합니다. 전체 620점이나 Milvus 전부를 버리지 않으며 진단 결함이 수정됐다고 단정하지 않습니다. 구체적인 대상은 [최신 결과 보고서](../07-results/fairness-v2-results-20260913.md)에 있습니다.

---

## 실행 중 CSV 갱신이 실패한다

Windows에서 CSV를 독점적으로 연 프로그램은 Java의 임시 파일 교체를 막을 수 있습니다. 파일을 연 도구를 확인하고, 실행 중 조회는 `FileShare.ReadWrite | FileShare.Delete`로 읽습니다. 과거 v1 실행의 로컬 `benchmark-result/sweep-20260911-220549/provenance/progress.ps1`이 그 예이며 현재 실행기가 자동으로 만드는 파일은 아닙니다. 일반 `Import-Csv` 예시는 실행 완료 뒤 사용합니다.

---

## Existing CSV schema is incompatible

```text
IllegalStateException: Existing CSV uses the old protocol; choose a new result directory: ...
```

결과 CSV 컬럼이 바뀌었는데 예전 디렉터리에 이어 쓰려 했습니다.

### 해결

새 디렉터리를 지정합니다.

```powershell
.\scripts\run-all-benchmarks.ps1 -ResultDirectory benchmark-result/rerun-01
```

---

## Recall이 0에 가깝다

낮은 Recall 자체는 실패가 아닙니다. 예상과 크게 다르면 원본 id 매핑, 입력 해시·차원·metric, 필터, 실제 적용한 검색 폭과 준비 상태를 확인합니다. id 불일치는 가능한 원인 중 하나입니다.

### 진단

```powershell
Get-Content benchmark-result/<dir>/raw/ground-truth-top10.jsonl -TotalCount 1
```

여기의 id와 `POST /api/search` 응답의 `id`를 비교합니다.

### 원인

Qdrant와 Weaviate는 내부 id로 UUID를 씁니다. 어댑터가 변환된 UUID를 반환하면 Recall이 0이 됩니다.
원본 id는 payload/property에서 읽어야 합니다.
[../06-implementation/adapter-policy.md](../06-implementation/adapter-policy.md)의 3번 규칙을 봅니다.

---

## Recall이 1.0에 가깝고 latency가 비정상적으로 낮다

높은 Recall과 낮은 latency만으로 오류를 판정하지 않습니다. 의도한 인덱스 경로가 맞는지 확인할 필요가 있을 때 다음 상태를 점검합니다.

| DB | 확인 |
|---|---|
| Qdrant | `indexed_vectors_count`가 전체 건수인지 |
| Milvus | index/load 상태와 query-node Sealed/Flushed segment row 합계 |
| pgvector | `enable_seqscan=off`가 적용됐는지 (`force-index-scan: true`) |

`awaitReady()`가 이 장벽을 담당하지만, 임계값 설정이 바뀌면 우회될 수 있습니다.

---

## ef를 올려도 latency가 변하지 않는다

검색 폭에 대한 latency 변화가 작을 수 있습니다. 미적용·다른 검색 경로 외에도 작은 데이터, 품질 포화, 클라이언트 비용, 반복 변동이 원인 후보입니다. 관측만으로 특정 원인을 확정하지 않습니다.

1. 어댑터가 파라미터를 실제로 전송하는지 확인합니다.
2. Weaviate는 요청별이 아니라 스키마 설정입니다. `configureSearch()`가 호출됐는지 확인합니다.
3. **특정 percentile만 무반응이면 필터 인덱스 누락을 의심합니다.**
   실제 사례는 [../07-results/analysis.md](../07-results/analysis.md)에 있습니다.

---

## Milvus가 뜨지 않는다

etcd와 MinIO가 healthy가 된 뒤에야 기동합니다. `start_period`가 90초입니다.

```powershell
docker compose --profile milvus ps
docker compose --profile milvus logs --tail=100 milvus
```

메모리 상한(6656 MiB)에 걸려 OOM으로 죽는 경우가 있습니다.

```powershell
docker inspect vector-milvus --format '{{.State.OOMKilled}}'
```

---

## OpenSearch가 yellow에서 멈춘다

single-node라 replica가 배치되지 않아 `yellow`가 정상입니다.
스크립트도 `wait_for_status=yellow`로 기다립니다.

`red`이면 로그를 확인합니다.

```powershell
Invoke-RestMethod 'http://localhost:9200/_cluster/health?level=indices'
```

---

## 임베딩 생성이 중간에 멈춘다

checkpoint의 원본·모델·부분 출력 근거가 일치하면 다시 실행해 이어서 생성할 수 있습니다. 이미 검증된 최종 벡터가 있으면 새 벤치마크를 위해 재생성하지 않습니다.

```powershell
.\gradlew.bat generateEmbeddings
```

```text
Resuming data/embeddings/document-vectors.jsonl.partial at record 5248
```

`Partial output has no checkpoint` 또는 `Final and partial embedding outputs coexist`는 재개 근거가 없거나 최종·부분 파일이 충돌한다는 뜻입니다. 먼저 관련 최종/부분/manifest/checkpoint를 별도로 보존하고 입력 해시·model digest·건수·출력 provenance를 확인합니다. 무조건 부분 파일을 삭제하거나 overwrite로 벡터를 바꾸면 이전 실험의 동일성 근거를 잃을 수 있습니다. 사용할 입력·출력을 확정한 뒤 새 출력 경로의 재생성 또는 검증된 기존 출력 복구를 선택합니다.

---

## 그래도 실패하면

수집할 정보:

```powershell
docker compose ps -a
docker compose logs --tail=200
Get-Content benchmark-result/<dir>/logs/*-application-error.log -Tail 100
Get-Content benchmark-result/<dir>/raw/benchmark-*.json |
  ConvertFrom-Json | Select-Object -ExpandProperty environment
```
