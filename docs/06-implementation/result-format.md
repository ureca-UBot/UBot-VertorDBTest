# 결과 형식

현재 형식은 `fairness-sweep-v2`입니다. 모든 파라미터·반복의 실제 측정을 보존합니다. **summary JSON에는 기존 `decision.eligible` 판정이 생성됩니다.** 코드의 기본 Recall 0.95·p95 30ms·RAM 2GiB와 holdout 미실시 등이 결합된 값이므로 선정(pgvector)에 사용하지 않았습니다. 30ms·2GiB는 합의된 서비스 조건이 아니며 해당 코드는 남아 있습니다.

## 디렉터리

```text
<result-directory>/
├─ protocol.txt
├─ raw/benchmark-<timestamp>-<uuid>.json
├─ raw/ground-truth-top10.jsonl
├─ raw/query-audit-<uuid>.jsonl.gz
├─ csv/vector-db-result.csv
├─ charts/recall-latency-latest.svg
├─ summary/vector-db-summary.json
├─ summary/vector-db-summary.csv
├─ failures/failure-<uuid>.json
├─ logs/
├─ api-response-run-*.json
└─ execution-order.json
```

각 점을 원시 JSON에 먼저 저장한 뒤 전체 원시값에서 CSV·집계·산포도를 다시 생성합니다. 같은 시각의 결과도 UUID로 구분합니다. 출력 파일 갱신은 임시 파일을 통한 교체로 수행하며 중간 실행 오류가 앞서 완료한 측정을 지우지 않습니다.

과거 CSV 스키마나 프로토콜 없는 과거 원시 파일이 있으면 새 디렉터리를 요구합니다. 이전 측정값을 새 프로토콜로 덮어쓰지 않습니다.

## 최신 완료 실행과 역사 자료

최신 실행 디렉터리는 `benchmark-result/fairness-v2-20260912-1826/`입니다. [최신 보고서](../07-results/fairness-v2-results-20260913.md)는 원시 620개 점과 동일 검색 설정 124개 그룹 × 5회, 전체 70개 독립 buildId를 기준으로 합니다. 합성 10k·1024차원·동시성 10만 측정했으며 1k/실서비스/독립 holdout의 완료 근거는 아닙니다.

[과거 v1 고정 사본](../07-results/assets/sweep-20260911-220549/README.md)의 `all-measurements.json`은 372개 점별 원시 JSON을 한 배열로 모은 파일입니다. 그곳의 `run-manifest.json`, `validation.json`, `run-status.json`, 소스 압축은 당시 추가한 보존 근거이며 일반 실행기가 모두 자동 생성하는 파일은 아닙니다. v1의 수치를 v2로 재라벨하거나 합산하지 않습니다.

## 원시 행

| 필드 | 의미 |
|---|---|
| testId, runNumber | 구성 식별자와 반복 번호 |
| database, engine, indexType | 실제 어댑터 |
| actualRecall | evaluation 전체의 scored 검색에 대한 경계 동점 인정 Recall |
| comparisonRecall | 무필터 Recall, 무필터가 없으면 전체 Recall로 대체하는 보조값 |
| searchParameters, indexParameters | 실제 검색 폭과 생성 설정 |
| averageLatencyMs, p50LatencyMs, p95LatencyMs, p99LatencyMs | 전체 evaluation의 요청 latency 통계 |
| qps | 성공 검색 수 / 전체 검색 작업 완료까지 걸린 시간; 실패 요청도 분모 시간에 포함 |
| measurementTimeMs, resourceSamples | 실제 검색 구간의 길이(ms), 그 구간 안에서 수집한 자원 표본 수 |
| filtered, unfiltered | 각 구간의 검색 수·Recall·latency와 빈 정답 검사 |
| averageCpuPercent, peakCpuPercent | 대상 컨테이너 합산 CPU 표본의 평균/최대 |
| averageMemoryBytes, peakMemoryBytes | 대상 컨테이너 합산 RAM 표본의 평균/최대 |
| diskWriteBytes, indexSizeBytes | 표본 사이 디스크 쓰기 증가량과 제품별 인덱스/store 크기; 미지원 -1 |
| indexBuildTimeMs, upsertTimeMs | 재구축부터 준비 완료까지, 적재 API 시간 |
| stabilityDiagnostics | 본 측정 뒤의 별도 직렬/동시성 Recall 표본과 상태; 본 측정 중 변동을 놓칠 수 있음 |
| audit | buildId·rebuilt·effectiveIndexState·warmupP95Ms/warmupStable·resourceComplete·검색/계약 오류·queryAuditFile |
| environment, measuredAt | 입력/질의 분할 SHA-256, 환경·자원 조건, 측정 시각 |

QPS 분모와 latency에는 Recall/계약 후처리와 결과 파일 직렬화가 포함되지 않습니다. Store 호출 시간에는 요청 변환·네트워크·응답 처리와 pgvector의 요청별 `set_config` SQL도 포함합니다. 자동 생성 단일 산포도는 혼합 p95와 actualRecall을 짝짓고 최신 보고서의 6개 산포도는 필터/무필터·자원 축까지 구분합니다.

최신 원시 CSV는 snake_case **55개 컬럼**이며 v1의 52개에 `filtered_strict_id_recall`, `unfiltered_strict_id_recall`, `audit`를 추가했습니다. 객체는 인용된 JSON 문자열입니다. 원시 행에는 `target_recall`, `recall_tolerance`, `target_met`, `calibration_selection`이 없습니다. Milvus 진단 안의 calibrationRecall은 진단용 첫 동시성 표본이며 목표 선택값이 아닙니다. summary JSON의 `decision`은 원시 target 필드와 다른 기존 코드 산출물입니다.

`raw/query-audit-*.jsonl.gz`는 질의별 반환 ID/score·Recall·위반·오류를 동일 응답별 실행 횟수로 묶은 감사 기록입니다. `ground-truth-top10.jsonl`은 strict Top-K와 K위 경계 동점 전체 ID를 보존합니다.

## 빈 정답 질의

| 구간별 컬럼 | 의미 |
|---|---|
| *_queries | 전체 검색 수 |
| *_scored_queries | Recall 평균에 들어가는 검색 수 |
| *_empty_ground_truth_queries | Exact 정답이 비어 있는 검색 수 |
| *_empty_ground_truth_violations | 정답이 없는데 행을 반환한 검색 수 |

`*_queries = *_scored_queries + *_empty_ground_truth_queries`입니다. 정답이 비어 있으면 재현할 순위가 없으므로 Recall에 1.0을 더하지 않습니다. 빈 결과 반환 여부를 따로 검사하고 원시 점은 보존합니다.

최신 evaluation의 고유 질의는 무필터 180개·필터 20개입니다. 혼합 Recall은 정답 있는 194개(무필터 180+필터 14)의 반복 검색을 채점하고 QPS는 200개 모두의 부하입니다. 필터 Recall의 분모는 14개이고 필터 p95는 빈 정답 6개를 포함한 20개 부하입니다. 파일의 실행 수는 이 질의들이 측정시간 동안 반복된 횟수입니다.

## 반복 집계

DB/engine/index, 생성 파라미터, **검색 파라미터**, 벡터 수, Top-K, 동시성, 워밍업·질의 반복 수, 데이터 해시·환경이 같은 측정만 한 행에 묶습니다. 원본 동기화의 실행별 시간 정보는 집계 키에서 제외합니다. test ID가 같아도 검색 파라미터가 다르면 다른 집계입니다.

JSON의 `metrics`와 CSV의 지표별 접미사에 다음을 기록합니다.

- samples: 해당 지표의 유효 표본 수
- mean, median, p95, p99, min, max
- sample_variance: n−1로 나눈 표본분산; 표본이 하나면 null

예를 들어 `p95_ms_median`은 각 독립 측정의 p95 latency들에 대한 median입니다. `p95_ms_p95`는 그 p95 값들 사이의 95분위수입니다. 요청을 합쳐 계산한 p95와 다릅니다.

원시 자원값 -1은 수집 불가입니다. 집계에서 유효 표본이 없으면 null(빈 CSV 셀)로 표시하며 원시값은 그대로 남깁니다. `completed_measurements`와 `run_numbers`는 실제 저장된 반복을 보여주며 Recall 통과 횟수가 아닙니다.

## 산포도

하네스의 `charts/recall-latency-latest.svg`는 X=혼합 p95(ms), Y=혼합 Recall@10의 모든 실제 점과 0.90·0.95 참고선을 표시합니다. 최신 보고서의 6개 산포도는 다음 축을 사용합니다.

| X축 | Y축 |
|---|---|
| 무필터 Recall@10 | 무필터 p95 |
| 혼합 Recall@10 | 혼합 QPS |
| 혼합 p95 | 혼합 QPS |
| 회차별 관측 peak RAM | 혼합 QPS |
| 평균 Docker CPU 코어 환산(저장된 % ÷ 100) | 혼합 QPS |
| 필터 Recall@10 | 필터 p95 |

작은 점은 실제 회차, 큰 기호는 같은 설정 5회의 Recall 평균/나머지 지표 중앙값입니다. 최소–최대 선은 반복 범위이지 신뢰구간이 아닙니다. 실행의 참고선 0.90·0.95 외에 최신 그림에는 0.80을 시각적 비교용으로 추가했습니다. 필요 시 ±0.01 같은 근접 범위와 실제 Recall 차이를 설명할 수 있지만 이번 sweep은 해당 범위로 자동 후보를 선택하거나 최근접값으로 대체하지 않았습니다. RAM은 합격선이 아닌 관측 지표이고 DB 실행 제한 8GiB·OpenSearch 힙 4GiB와 구별합니다.

워밍업 미달 170개와 query audit으로 확인한 Milvus DISKANN 변동 5개를 숨기지 않습니다. 사후 `stabilityDiagnostics.verified=true`만으로 이 변동을 정상이라고 덮지 않으며 의심 결과는 확정 판단에 쓰지 않습니다. 서비스 선정 결과와 근거는 [선정 결론](../07-results/decision.md)에 있습니다.

[과거 84개 결과](../07-results/matrix-results-20260911.md)의 스키마와 목표 관련 필드는 역사 자료에만 남습니다. 그 원본을 새 sweep 측정으로 해석하지 않습니다.
