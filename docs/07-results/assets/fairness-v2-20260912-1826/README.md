# 최신 620개 측정의 문서 근거

[보고서](../../fairness-v2-results-20260913.md)에 사용한 문서용 자료다. 원본은 저장소의 `benchmark-result/fairness-v2-20260912-1826/`이며 이 폴더는 그림과 지표를 검토하기 위한 별도 사본이다. 원시 측정·JAR·벡터를 변경하거나 DB를 다시 실행하지 않았다.

## 파일별 역할

| 파일 | 역할 |
|---|---|
| [scatter-interactive.html](scatter-interactive.html) | 앞서 확인한 6개 상호작용 산포도 보존본. 데이터 포함, D3 등 표시 라이브러리는 CDN 연결 필요 |
| [scatter-multi-axis.png](scatter-multi-axis.png) | 기본 상태의 6개 그림과 범례. 오프라인 문서용 |
| scatter-recall-latency.png 등 6개 | 각 축 조합의 확대 이미지. 색·기호 범례는 전체 그림 참고 |
| [scatter-readme.png](scatter-readme.png) | 저장소 README용. 상호작용 산포도의 제목·범례·①② 패널을 Edge headless(가로 1280px, 2배율)로 잘라 낸 이미지. 아래 스크립트가 다시 만들지 않으며 해시 목록 대상도 아님 |
| [scatter-data.json](scatter-data.json) | 14개 구성·124개 설정·620회차, 9개 축 지표. 표시용 반올림 데이터 및 경고 |
| [measurements.json](measurements.json) | 원시 JSON의 전체 정밀도 지표 620행. p50/p95/p99, 자원 평균/최대, 구축·적재·index size, 분할 지표, 생성/검색 파라미터 포함 |
| [parameter-statistics.md](parameter-statistics.md) | 124개 고정 설정의 사람이 읽는 요약 표 |
| [parameter-statistics.json](parameter-statistics.json) | 위 124개 설정의 평균·중앙값·최소·최대, 무필터·필터·CPU·RAM 포함 |
| [method-ranges.json](method-ranges.json) | 구성별 모든 검색 설정·5회차를 합한 관측 범위. 하나의 설정 성능이나 반복 변동으로 해석하지 않음 |
| [build-statistics.json](build-statistics.json) | buildId 중복 제거한 70개 구축과 구성당 5회 준비시간 집계 |
| [execution-start.json](execution-start.json), [execution-order.json](execution-order.json) | 실행 시작 명세와 교차 DB 순서의 의미상 동일 JSON 사본 |
| [validation.json](validation.json) | 620행·124설정·70build·5,580개 수치 대조, 오류/경고, 측정 구간·표본 수, 원시 파일 해시 |
| [render-validation.json](render-validation.json) | 6패널, 패널당 620관측/124요약, 범례·선택·반복표·Escape·360px 표시 확인 |
| [artifact-hashes.json](artifact-hashes.json) | 이 폴더 JSON/PNG/HTML의 SHA-256. 자체 파일과 Markdown 설명은 제외 |

measurements.json은 **원본 raw JSON 전체의 바이트 동일 사본이 아니다.** 지표의 숫자 정밀도와 생성·검색 설정은 유지하고 큰 environment/effectiveIndexState/상세 진단 본문은 제외했다. sourceFile과 queryAuditFile로 원래 파일을 추적한다. 전체 상태·진단·압축 질의 감사·로그는 원본 실행 디렉터리에 있다. 이 폴더만으로 실행 환경 전체를 재현할 수는 없다.

## 채점·집계·경고

- 혼합 Recall은 빈 정답을 제외한 194개 질의, 혼합 QPS는 200개 전체 부하다. 필터 Recall은 정답 있는 14개, 필터 p95는 빈 정답 6개 포함 20개다.
- 그림의 Recall은 경계 동점을 인정한다. strict-ID 지표는 회차별 원시 지표에 남긴다. 9월 11일 결과와 채점이 달라 단순 합산하거나 성능 개선율을 계산하지 않는다.
- 큰 점은 Recall 평균과 다른 축 중앙값을 조합한 요약이며 실제 한 회차가 아니다. 작은 점과 선택 범위가 5회 변동을 설명한다. 범위는 신뢰구간이 아니다.
- 워밍업 경고 170개와 Milvus DISKANN 확인 필요 5개를 보존한다. suspect 표시는 앞선 질의 감사·동일 질의 반복 진단 검토에서 식별한 것이다. 재생성 스크립트는 이 표식의 보존·개수를 검사하며 압축 query audit 전체를 재채점하거나 Milvus 원인을 재진단하지 않는다.
- 그림 데이터의 영문 suspectDefinition에 남은 within-measurement 표현을 상태가 측정 내내 같았다는 검증으로 읽지 않는다. 상세 해석과 진단 한계는 본 보고서 §5를 따른다.
- 원시 최신 summary의 decision.eligible은 합의되지 않은 2GiB/30ms 기본값과 holdout 여부를 섞으므로 제품 판정·점 제거에 쓰지 않는다. 문서 작업에서 판정 코드를 변경하지 않았다.

## 수치 재검증과 그림 재생성

저장소 루트에서 Node.js로 실행한다. JSON 집계만 할 때 브라우저·Docker는 필요하지 않다. 원본 실행 디렉터리는 필요하다. 스크립트는 이 폴더의 생성 자료만 갱신한다.

```powershell
node scripts/build-fairness-doc-assets.cjs
```

그림까지 다시 만들려면 Playwright와 Microsoft Edge, CDN 접근이 필요하다. Playwright가 모듈 검색 경로에 없다면 PLAYWRIGHT_MODULE에 설치 모듈의 절대경로를 지정한다. 원본의 DB 검색을 다시 실행하지는 않는다.

```powershell
node scripts/build-fairness-doc-assets.cjs --render
```

```powershell
$docAssetDirectory = 'docs/07-results/assets/fairness-v2-20260912-1826'
$docArtifacts = Get-Content (Join-Path $docAssetDirectory 'artifact-hashes.json') -Raw | ConvertFrom-Json
foreach ($docArtifact in $docArtifacts) {
    $docActualHash = (Get-FileHash -LiteralPath (Join-Path $docAssetDirectory $docArtifact.file) -Algorithm SHA256).Hash
    if ($docActualHash -ine $docArtifact.sha256) { throw "Hash mismatch: $($docArtifact.file)" }
}
```

검증 스크립트는 [build-fairness-doc-assets.cjs](../../../../scripts/build-fairness-doc-assets.cjs)에 있다. 신규 서비스 검증·DB 선택을 자동 수행하는 스크립트가 아니다.
