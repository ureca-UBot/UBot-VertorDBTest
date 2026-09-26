# Local Setup

## 완료 기준

이 문서를 마치면 `data/embeddings/`에 1024차원 벡터 파일이 생기고
`/actuator/health`가 `{"status":"UP"}`을 반환합니다.

## 사전 요구사항

| 항목 | 버전 | 확인 |
|---|---|---|
| Java | 21 | `java -version` |
| Docker | 27+ | `docker version --format '{{.Server.Version}}'` |
| PowerShell | 5.1+ | `$PSVersionTable.PSVersion` |
| Ollama | `bge-m3:latest` 설치 | `ollama list` |

사용 포트: 8080(앱), 5432(PostgreSQL), 6333(Qdrant), 18080(Weaviate),
19530·9091(Milvus), 9200(OpenSearch), 19200(OpenSearch JVector), 11434(Ollama)

## 1. 저장소 준비

```powershell
git clone <repo>
cd UBot-VertorDBTest
Copy-Item .env.example .env
```

## 2. PostgreSQL 기동

Source of Truth이자 pgvector 프로필의 측정 대상입니다. 모든 프로필에서 필요합니다.

```powershell
docker compose up -d postgres --wait
```

### 확인

```powershell
docker compose ps postgres
# expected: STATUS 열이 "healthy"
```

## 3. 임베딩 생성

```powershell
ollama pull bge-m3
.\gradlew.bat generateEmbeddings
```

합성 청크 10,000개 + 질의 300개를 128건 배치로 처리합니다. 이미 설치된 동일 모델과 검증된 벡터 파일이 있다면 다시 다운로드하거나 생성할 필요가 없습니다. 중간 생성 재개는 checkpoint의 원본·부분 출력 SHA-256·model digest·건수가 모두 일치할 때만 허용합니다.

### 확인

```powershell
Get-Content data/embeddings/embedding-manifest.json | ConvertFrom-Json |
  Select-Object model, dimension, @{N='docs';E={$_.documentOutput.records}}
```

```text
model          dimension docs
-----          --------- ----
bge-m3:latest       1024 10000
```

`minL2Norm`과 `maxL2Norm`이 1에 가까우면 정상입니다.

> 완성된 출력은 provenance 또는 검증 가능한 기존 manifest의 원본/출력 해시·model digest·차원·건수를 확인한 뒤 재사용합니다. 근거 없는 출력은 새 입력으로 재라벨하지 않고 거부합니다.
> 의도적으로 다시 만들 때만 `-PoverwriteEmbeddings=true`를 씁니다.
> **이 옵션은 기존 벡터를 교체하므로 이전 실험 결과와 비교할 수 없게 됩니다.**

## 4. 빌드

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
```

### 확인

```powershell
Test-Path build/libs/VectorDBTest-0.0.1-SNAPSHOT.jar
# expected: True
```

## 5. 앱 기동 확인

```powershell
docker compose --profile qdrant up -d qdrant
.\gradlew.bat bootRun --args="--spring.profiles.active=qdrant"
```

다른 창에서:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/search/store
```

```text
status
------
UP

database
--------
qdrant
```

## 실패했다면

| 증상 | 문서 |
|---|---|
| DB 연결 실패, 포트 충돌, 자원 예산 불일치 등 | [../08-troubleshooting/common-issues.md](../08-troubleshooting/common-issues.md) |

## 다음 단계

- 벤치마크 실행 → [run-benchmark.md](run-benchmark.md)
- 현재 실행 조건 → [fairness-v2](../03-benchmark-design/fairness-v2.md)
- 재실행 없이 최신 620개 측정 보기 → [결과 보고서](../07-results/fairness-v2-results-20260913.md)
- DB별 설정 → [../05-databases/](../05-databases/)
