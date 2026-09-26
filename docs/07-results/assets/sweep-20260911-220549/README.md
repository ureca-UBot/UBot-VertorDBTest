# 전체 sweep 실측 근거 — sweep-20260911-220549

> **현재 기준 안내 (2026-09-13):** 최신 결과는 [fairness-v2 620개 측정·6축 산포도](../../fairness-v2-results-20260913.md)다. 아래는 2026-09-11 당시 실행·해석을 보존한 역사 자료이며 수치와 판정 표현을 최신 결과에 적용하지 않는다. 채점·측정·어댑터 조건이 달라 최신 620개와 합산하지 않는다.

[실행 보고서](../../sweep-results-20260911.md)에 사용한 고정 사본입니다. 2026-09-11 22:06:19–23:47:05(KST)에 14개 구성을 각각 3회 재구축해 372개 점을 측정했습니다. 원본 실행 디렉터리는 저장소의 `benchmark-result/sweep-20260911-220549/`입니다.

## 측정값과 그림

| 파일 | 역할 |
|---|---|
| [vector-db-result.csv](vector-db-result.csv) | 전체 372개 원시 행, 52개 컬럼 |
| [all-measurements.json](all-measurements.json) | 점별 원시 JSON을 한 배열로 모은 372개 측정; 전체 정밀도와 환경·진단 포함 |
| [vector-db-summary.json](vector-db-summary.json) / [CSV](vector-db-summary.csv) | 동일 파라미터 124개 그룹 × 3회; 지표별 평균·median·p95/p99·분산·표본 수 |
| [산포도 SVG](scatter-recall-latency-all-372.svg) | 원본 실행의 SVG와 동일한 사본; X=전체 p95 ms, Y=actual Recall@10 |
| [산포도 PNG](scatter-recall-latency-all-372.png) | SVG를 2800×1640으로 렌더링한 문서용 이미지 |

SVG에는 실제 점 372개와 Recall 0.90·0.95 수평 참고선 2개가 있습니다. 겹친 점도 저장돼 있으며, 점에 마우스를 올리면 검색·생성 파라미터, 회차, latency, QPS, CPU·RAM을 확인할 수 있습니다. 집계 중앙값을 새로운 실측 점으로 추가하지 않았습니다.

## 실행과 검증 근거

| 파일 | 역할 |
|---|---|
| [run-manifest.json](run-manifest.json) | 프로토콜, 3회 반복, 최소 5초, 입력·행렬·JAR·실행 스크립트 SHA-256 |
| [benchmark-matrix.json](benchmark-matrix.json) | 실행 당시 14개 구성·124개 파라미터 그리드 |
| [execution-start.json](execution-start.json) / [run-status.json](run-status.json) | 실제 시작·완료 시각과 완료 개수 |
| [execution-order.json](execution-order.json) | 회차별 DB 실행 순서 |
| [validation.json](validation.json) | 372개 원시값·산포도 좌표 일치, 그리드·반복·입력 해시·부하 조건 검증 |
| [verify-results.ps1](verify-results.ps1) | 원본 실행 디렉터리에 적용한 검증 스크립트; 10k·3회·372개 조건에 맞춘 검사 |
| [unit-test-results.json](unit-test-results.json) | 실행 전 자동 테스트 42개 통과 기록 |
| [source-snapshot.zip](source-snapshot.zip) | 실행 소스·테스트·설정·Gradle·Docker·스크립트 사본; 벡터 데이터 자체는 포함하지 않음 |
| [artifact-hashes.json](artifact-hashes.json) | 보존한 데이터·그림·실행 근거 15개 파일의 SHA-256; 이 안내 문서는 해시 목록 대상이 아님 |

검증 시 전체 측정 요청은 2,948,000회, 검색 구간은 점당 최소 5,001ms, 자원 표본은 최소 2개였습니다. CPU·RAM 누락, 실행 오류, 빈 정답 반환 위반은 모두 0건입니다. 원본의 로그·점별 파일·Ground Truth는 로컬 실행 디렉터리에 남아 있습니다.

## 해시 확인

저장소 루트에서 다음을 실행하면 고정 사본이 기록된 해시와 같은지 확인합니다.

```powershell
$assetDirectory = 'docs/07-results/assets/sweep-20260911-220549'
$manifest = Get-Content (Join-Path $assetDirectory 'artifact-hashes.json') -Raw | ConvertFrom-Json
foreach ($item in $manifest) {
    $actual = (Get-FileHash -LiteralPath (Join-Path $assetDirectory $item.file) -Algorithm SHA256).Hash
    if ($actual -ine $item.sha256) { throw "Hash mismatch: $($item.file)" }
}
```

원본 실행 전체를 검증하려면 로컬 `benchmark-result/sweep-20260911-220549/provenance/verify-results.ps1`을 해당 결과 디렉터리의 `-ResultDirectory`로 실행합니다. 이 문서용 사본은 원본의 `raw/`, `charts/`, `provenance/` 구조와 다르므로 사본 디렉터리를 그대로 넘기지 않습니다.

## 이전 자료와의 구분

계측 수정 전 시도 `sweep-20260911-215805`의 26개 원시 측정과 중단 사유는 별도 로컬 디렉터리에 보존했습니다. 검색 종료 뒤 유휴 CPU 표본이 섞이던 계측을 수정하고 이번 372개를 처음부터 다시 측정했습니다. 이전 목표별 선택 방식의 [84개 자료](../matrix-20260911/README.md)도 별도 보존합니다. 이 두 자료를 이번 산포도에 합치지 않았으며 Recall 값 때문에 제거한 측정은 없습니다.
