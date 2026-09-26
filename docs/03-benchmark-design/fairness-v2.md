# 공정성 보강 프로토콜 — fairness-sweep-v2

2026-09-13 문서 검토 기준입니다. [완료한 v2 실측](../07-results/fairness-v2-results-20260913.md)은 합성 10k·1024차원·concurrency 10에서 **14개 구성·124개 검색 설정 × 독립 재구축 5회 = 620점**입니다. 결과 디렉터리는 `benchmark-result/fairness-v2-20260912-1826`입니다. 2026-09-11의 372점은 `search-parameter-sweep-v1` 역사 자료로 그대로 보존합니다. 채점·응답 크기·측정 시간이 달라졌으므로 v1과 v2를 합산하거나 기존 수치를 새 기준으로 재라벨하지 않습니다.

프로젝트 예상 운영 범위는 **1,000~10,000청크**입니다. 10k 실측은 이 범위 상단의 탐색 근거이며 1k·실제 서비스 데이터·새 holdout은 아직 측정하지 않았습니다. 100k/1M은 필요 시 성장 검증용 선택 축입니다.

## 탐색과 최종 검증을 구분합니다

`exploratory`는 기존 14개 구성의 전체 grid를 공개하는 실험입니다. Recall 참고선은 0.90/0.95이며 낮은 Recall도 저장합니다. 이 결과를 보고 후보를 골랐다면 같은 질의를 독립적인 최종 평가에 다시 쓸 수 없습니다.

기존 300개 질의의 calibration 100 / evaluation 200 분할은 유지하지만 이번 sweep은 calibration으로 검색 폭을 고르지 않습니다. calibration은 Milvus 별도 진단에 쓰고 evaluation은 모든 검색 파라미터의 워밍업·본 측정에 씁니다. 과거 fixed-target 실험의 목표 ±0.01·최근접값 선택 정책을 이번 실행 경로의 규칙으로 설명하면 안 됩니다. 산포도에서 Recall이 비슷한 실제 설정의 p95/QPS/RAM과 5회 변동을 읽는 탐색 비교입니다.

`holdout`은 검색 파라미터를 미리 고정한 JSON plan과 새 query 파일을 요구합니다. 입력 SHA-256, 선언된 index/search 설정, Top-K, concurrency가 plan과 일치해야 합니다. 이전 선택용 질의와 ID·정규화 텍스트·벡터가 겹치면 거부합니다. 같은 원문에서 나온 paraphrase·semantic/source-group 중복까지 자동 검증하지는 않으므로 별도 데이터 출처 감사를 수행해야 합니다. 기존 300개 질의를 분할만 다시 하는 것은 새 holdout이 아닙니다.

## 채점·응답 계약

- 실제 Recall은 경계 동점을 인정합니다. 원본 벡터로 정확 검색을 수행하고 K위보다 좋은 ID 집합 A, K위 동점 전체 B, 반환 ID 집합 R에 대해 `(R∩A 수 + min(K-A 수, R∩B 수))/K`를 사용합니다. K는 eligible 문서가 적으면 그 수로 줄입니다.
- 동점 허용 오차 기본값은 **0**이며 사전에 설정한 `benchmark.tie-tolerance`를 기록합니다. 더 좋은 이웃을 놓친 것을 동점 여러 개로 보상하지 않습니다.
- 기존 ID 교집합 Recall은 `filtered.strictIdRecall`, `unfiltered.strictIdRecall`에 함께 저장합니다. 빈 정답의 Recall은 null/평균 제외이며 no-hit 반환 위반을 별도 집계합니다.
- 중복·unknown ID, document/chunk 불일치, 필터 위반, 비유한 score, topK 초과는 계약 위반입니다. 해당 응답은 Recall 0으로 채점하고 최종 후보에서 제외합니다.
- 반환 필드는 id/documentId/chunkId/score입니다. Qdrant는 본문·전체 metadata를 반환하지 않습니다. JSON 숫자는 표현(2/2.0)에 관계없이 비교하고 문자열·boolean은 숫자로 강제 변환하지 않습니다. metadata key별 혼합 타입은 사전 거부합니다.

## 반복·시간·자원

- 기본 **5회 전체 재구축**입니다. API도 `rebuildAndLoad=true`이면 repetition마다 drop/create/load/ready를 반복합니다. false이면 구축 비용은 0이 아니라 -1(미측정)입니다. 각 구축에는 별도 buildId가 붙습니다.
- DB 순서는 회차별 교차, 검색 파라미터 순서는 runNumber를 시드로 결정적으로 섞습니다.
- 측정 concurrency로 워밍업하며 최근 3회 p95 범위가 median의 15% 이내인지 확인합니다. 기본 최대 10회이며 불안정 상태도 결과에 남깁니다. 워밍업 생략은 최종 판정 불가입니다.
- 기본 최소 **30초 + 검색 구간에 완전히 포함된 자원 표본 30개**를 확보합니다. 표본이 부족하면 기본 180초까지 완전한 query batch 단위로 연장합니다. 마지막 batch/개별 요청 timeout 때문에 180초는 강제 중단 시간이 아닙니다. 표본 부족은 `resourceComplete=false`입니다.
- QPS는 성공한 검색 수 / 검색 workload 경과시간입니다. Recall·응답 검증·감사 파일 저장은 타이머 종료 후입니다. 오류 요청 latency도 전체 latency에 포함하며 실패 수를 기록합니다. 오류가 있으면 해당 점과 실패 기록을 보존한 뒤 다음 점 실행을 중단합니다.
- DB 합계 4 vCPU/8 GiB/swap 없음, Milvus는 etcd·MinIO 포함. OpenSearch의 4GiB JVM 힙은 이 8GiB 컨테이너 예산 안의 설정입니다. CPU/RAM Max는 **관측 표본의 최대값**이지 실제 순간 peak를 보장하지 않습니다. RAM은 비교 지표이며 2GiB 초과라는 이유로 이번 문서의 구성을 탈락시키지 않습니다.
- `indexBuildTimeMs`는 순수 index build만이 아니라 drop/create/load/ready 전체 시간입니다. `upsertTimeMs`는 적재 구간입니다. Index Size는 어댑터별 제공 범위가 다르고 미지원은 -1이므로 순수 ANN 구조 크기로 일괄 해석하지 않습니다.

## 산출물과 판정

원시 JSON/CSV/산포도는 모든 점을 보존합니다. `audit`에 buildId, 실제 DB 설정/상태 응답, 워밍업 p95, 오류·계약 위반, 자원 완전성을 기록합니다. `raw/query-audit-*.jsonl.gz`는 질의별 반환값/Recall/오류를 동일 응답별 실행 횟수로 묶어 저장합니다. 정답 파일에는 strict top-K와 전체 경계 동점 ID가 있습니다.

summary JSON은 동일 workload·설정별 median p95/QPS, Recall mean/min/max, 오류 수 분포와 `decision`을 제공합니다. **현재 코드의 `decision`과 이번 문서의 비교 해석은 구분합니다.** `DecisionGate`는 holdout 미실시·워밍업 경고·자원 증거·Recall/p95/RAM 조건을 하나의 `eligible`로 합칩니다. 임계값이 없는 탐색 결과에도 Recall 0.95·p95 30ms·RAM 2GiB를 기본 적용합니다. 특히 **30ms와 2GiB는 합의된 서비스 SLO가 아니므로 이 보고서의 탈락·순위 기준으로 사용하지 않습니다.** 코드와 기존 summary는 아직 수정하지 않았습니다.

이번 분석에서는 다음을 분리합니다.

- 측정 무결성: 입력·설정 일치, 5개 독립 재구축, 30초/30표본, 검색 실패·응답 계약 위반 등을 확인합니다. 이번 620점은 검색 실패·응답 계약 위반·자원 수집 누락이 0건입니다.
- 품질 경고: 워밍업 기준 미충족 **170점**은 모두 표시하고 보존합니다. 통과한 워밍업도 본 측정 안정성을 보증하지 않습니다. Milvus의 사후 진단 `verified=true`만으로 측정 중 변동이 없다고 단정하지 않으며 **DISKANN 5점**은 재현성 확인 대상으로 표시합니다.
- 탐색 비교: 원시 산포도 620점과 같은 설정의 5회 집계 124그룹을 구분합니다. Recall이 비슷한 설정끼리 지연·처리량·자원과 반복 범위를 비교하고 자동 승자나 단일 점수를 만들지 않습니다.
- 검증 단계: 이번은 `exploratory`입니다. 1k·실서비스·새 holdout 미실시는 아직 하지 않은 검증이지 DB 실패가 아닙니다.
- 서비스 적합성: 합의한 SLO가 없는 항목은 미판정입니다. 향후 SLO를 정할 때 대상 workload·필터 slice·5회 평균/median인지 모든 회차 최악값인지까지 먼저 합의해야 합니다.

이 구분은 기존 관측치를 해석하는 정책이며 원시 수치 재계산이나 재측정은 아닙니다. 경고점만 빼서 좋은 값으로 바꾸거나 현재 데이터를 새 holdout으로 승격하지 않습니다.

## 실행

```powershell
.\gradlew.bat --offline test
.\gradlew.bat bootJar
.\scripts\run-all-benchmarks.ps1 -Repetitions 5 -ResultDirectory benchmark-result/fairness-v2-new
```

향후 holdout plan은 실제 파일 SHA-256과 사전에 고정한 전체 index/search 설정을 담습니다. 과거 문서 예시의 2GiB·30ms를 요구사항으로 복사하지 않습니다.

| plan 필드 | 의미 |
|---|---|
| documentVectorsSha256 / queryVectorsSha256 / queryDefinitionsSha256 | 입력 문서·새 질의 벡터·질의 정의의 실제 해시 |
| priorQueryVectors / priorQueryDefinitions 및 각각의 Sha256 | 이전 선택용 질의 파일과 해시; 새 holdout과 겹침 검사 |
| scenarios | testId·database·engine·indexType·topK·concurrency·전체 index/search 설정 |
| thresholds | 사전에 합의할 Recall·지연·자원·오류율 정책; 현재 서비스 요구사항으로 확정된 값 없음 |

주의: **현재 `HoldoutGuard`는 recallMinimum·p95MaximumMs·ramMaximumBytes·errorRateMaximum을 필수 숫자로 요구합니다.** RAM을 관측 지표만으로 두는 정책은 아직 이 실행 스키마에 반영되지 않았습니다. 새 holdout을 실행하기 전에 정책과 코드 경로를 별도로 정리해야 하며, 검증기를 통과하려고 임의의 임계값을 넣지 않습니다. concurrency 1/10은 같은 ID의 scenario를 각각 선언할 수 있습니다. prior 상대 경로는 plan 파일의 부모 기준입니다.

정책·스키마를 정리하고 새 입력·plan을 준비한 뒤 사용할 실행 형식은 다음과 같습니다. 이번 완료 실행에서 holdout을 수행했다는 뜻은 아닙니다.

```powershell
.\scripts\run-all-benchmarks.ps1 -Mode holdout -ValidationPlan data/frozen-plan.json `
  -DocumentVectors D:\dataset\documents.jsonl -QueryVectors D:\dataset\unseen-vectors.jsonl `
  -QueryDefinitions D:\dataset\unseen-queries.jsonl -ResultDirectory benchmark-result/holdout-new
```

실제 서비스 1k~10k 입력과 질의·필터 분포가 다음 검증 대상입니다. 1%/10%/50% 선택도 wrapper와 필요 시 100k/1M 확장은 별도 탐색 검증이며 실행했다고 자동으로 holdout 검증이 되지 않습니다. 현재 데이터만으로 미실시 축이 완료됐다고 주장하지 않습니다.
