# 벤치마크 실행

목적은 전체 검색 파라미터의 정확도·지연·처리량·자원 산포도를 보고 같은 Recall 수준의 후보를 비교하는 것입니다. [fairness-v2 프로토콜](../03-benchmark-design/fairness-v2.md)을 기준으로 하며 과거 84개·372개 결과는 역사 자료입니다. 최신 완료 결과만 보려면 [620개 다축 산포도 보고서](../07-results/fairness-v2-results-20260913.md)를 열면 되고 재측정은 필요하지 않습니다.

## 전체 sweep

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
.\scripts\run-all-benchmarks.ps1 `
  -Repetitions 5 `
  -MinimumMeasurementTimeMs 30000 `
  -MinimumResourceSamples 30 `
  -ResultDirectory benchmark-result/fairness-v2-new
```

14개 구성의 124개 파라미터를 재구축마다 측정합니다. 기본 5회는 총 70회 인덱스 재구축·620개 점입니다. [최신 완료 실행](../07-results/fairness-v2-results-20260913.md)도 이 조건입니다. `Repetitions`는 3~5이며 회차별 DB 순서와 검색 파라미터 순서를 교차합니다. 30초와 자원 30표본을 함께 만족해야 하므로 각 점은 30초보다 오래 걸릴 수 있습니다. 종료 시 벤치마크 DB를 중지하고 Ollama는 유지합니다.

한 인덱스의 모든 파라미터만 실행할 수도 있습니다.

```powershell
.\scripts\run-all-benchmarks.ps1 `
  -TestIds T03,T09 `
  -Repetitions 3 `
  -ResultDirectory benchmark-result/sweep-subset
```

과거 T02/T04 등 목표별 짝수 행은 제거했습니다. 현재 ID는 T01, T03, …, T27입니다. 새 실험마다 새 결과 디렉터리를 사용합니다.

## 직접 API 호출

DB 프로필과 데이터 경로를 설정해 실행한 애플리케이션의 `POST /api/benchmarks/run`에 다음 본문을 보냅니다. 검색 파라미터 이름은 활성 어댑터가 정합니다.

```json
{
  "rebuildAndLoad": true,
  "scenarios": [{
    "topK": 10,
    "concurrency": 10,
    "warmupIterations": 1,
    "measurementIterations": 5,
    "repetitions": 3,
    "searchParameterValues": [16, 32, 64, 96, 128],
    "searchParameters": {}
  }]
}
```

현재 API는 `rebuildAndLoad=true`이면 repetition마다 drop/create/load/ready를 다시 수행합니다. 위 호출은 3회 재구축 × 5개 검색 파라미터 = 15개 측정입니다. false이면 인덱스 재사용이며 구축 비용은 -1(미측정)입니다. 외부 실행 스크립트는 `repetitions=1`로 호출하며 재구축을 직접 반복합니다. 그리드 생략 시 `benchmark.search-parameter-values`를 사용합니다. IVF 등에는 어댑터 범위에 맞는 그리드를 명시합니다.

고정 파라미터만 반복하려면 `searchParameterValues`를 생략하고 `searchParameters`에 실제 어댑터의 키와 값을 넣습니다. 두 방식을 동시에 지정하면 오류입니다.

## 저장된 이번 결과 확인

새 테스트를 돌리지 않고 확인하려면 [최신 620개 산포도와 실행 근거](../07-results/fairness-v2-results-20260913.md)를 엽니다. 로컬 실행 디렉터리는 `benchmark-result/fairness-v2-20260912-1826/`입니다. 이 완료 디렉터리를 재실행 출력으로 재사용하지 않습니다.

## 결과 확인

하네스 자동 출력 `charts/recall-latency-latest.svg`는 X=혼합 p95(ms), Y=혼합 Recall@10인 기본 산포도이고 0.90·0.95 참고선을 표시합니다. 최신 보고서의 6개 다축 산포도는 이와 별도로 모든 620개 회차와 124개 동일 설정 요약을 보여줍니다. Recall 0.80·0.90·0.95는 참고선이며 합격선이 아닙니다.

```powershell
Import-Csv benchmark-result/fairness-v2-20260912-1826/summary/vector-db-summary.csv |
  Select-Object database,engine,index,search_parameters,completed_measurements,
    recall_mean,recall_min,recall_max,p95_ms_median,qps_median,
    cpu_average_percent_mean,ram_max_bytes_median |
  Format-Table -AutoSize
```

위 `Import-Csv` 예시는 실행 완료 뒤 사용합니다. Windows에서 갱신 중인 CSV를 읽을 때는 파일 교체를 막지 않도록 `FileShare.ReadWrite | FileShare.Delete`로 읽는 방식을 사용합니다.

원시 행은 `csv/vector-db-result.csv`, 측정별 JSON은 `raw/`, 오류는 `failures/`, 로그는 `logs/`입니다. 중간 오류 전까지 완료한 점은 이미 저장되어 있습니다. 동일 파라미터의 반복을 집계하며 Recall 값 때문에 행을 제거하지 않습니다.

## 과거 측정 다시 그리기

```powershell
.\gradlew.bat renderBenchmarkChart `
  '-PchartInput=benchmark-result/matrix-t01-t28-20260911-183751' `
  '-PchartOutput=benchmark-result/historical-replot.svg'
```

새 출력 파일 경로를 지정해야 합니다. 과거 JSON의 실제 Recall과 p95를 그대로 사용하며 원본이나 기존 그림을 덮어쓰지 않습니다. 이 작업은 새 DB 측정이 아닙니다.

## 추가 workload

필터 선택도 스크립트는 같은 임베딩에 1%/10%/50% cohort를 적용합니다.

```powershell
.\scripts\run-filter-selectivity-benchmarks.ps1 -TestIds T01,T05,T07 -Repetitions 3
```

현재 프로젝트 범위는 1k~10k 청크이므로 실제 프로젝트 입력·질의 분포의 검증을 우선합니다. non-synthetic 입력은 `run-real-workload-validation.ps1`로 검사합니다. 100k/1M은 향후 규모 확장 시 선택적으로 실제 벡터 파일을 `run-shortlist-scale-validation.ps1`에 전달하는 도구이며 지금의 필수 단계가 아닙니다. wrapper를 실행한 것만으로 독립 holdout 검증이 되지는 않습니다.

CPU/RAM -1은 수집 불가, 집계의 null은 유효 표본 없음입니다. 기존 summary JSON의 `decision.eligible`에는 합의되지 않은 기본 2GiB·30ms 등의 판정이 남아 있어 현재 선정에 적용하지 않습니다. Milvus의 사후 안정성 진단은 본 측정 중 변동을 놓칠 수 있으므로 경고를 함께 봅니다. [결과 형식](../06-implementation/result-format.md)에 모집단·단위·잔여 한계를 설명합니다.
