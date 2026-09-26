# Chunk와 Embedding

## Chunk

긴 문서를 검색 단위로 자른 조각입니다. RAG에서 검색과 인용의 단위는 문서 전체가 아니라 chunk입니다.

이 프로젝트의 데이터는 이미 chunk 단위로 제공됩니다.

```json
{
  "id": "chunk-000-00",
  "document_id": "doc-000",
  "chunk_id": "chunk-000-00",
  "content": "Spring Transaction 요청의 p95 지연시간이 증가한다. ...",
  "category": "spring-tx",
  "tenant_id": "alpha",
  "status": "active",
  "language": "ko"
}
```

`id`가 검색 결과의 식별자이자 Recall 계산의 기준입니다.

## Embedding

chunk의 `content`를 모델에 넣어 벡터를 얻는 과정입니다.
이 프로젝트는 로컬 Ollama의 `bge-m3:latest`로 **한 번만** 생성하고 파일로 고정합니다.

```powershell
.\gradlew.bat generateEmbeddings
```

생성기(`tools/EmbeddingDatasetGenerator.java`)가 하는 일:

- 기본 128건 배치, transient 오류 재시도, 실패 배치 자동 분할
- checkpoint 기반 재개 — 중간에 끊겨도 이어서 생성
- 전체 레코드의 차원·유한값·zero vector 검증
- 완성된 출력이 있으면 입력·모델·출력 provenance와 해시·레코드 수 등을 검증하고 재사용; 출처 불명 출력은 거부

의도적으로 다시 만들 때만 `-PoverwriteEmbeddings=true`를 씁니다.
이 옵션은 기존 벡터를 교체하므로 모델이나 원본을 바꾼 경우에만 사용합니다.

## 생성 결과

```text
data/embeddings/document-vectors.jsonl   10,000건
data/embeddings/query-vectors.jsonl         300건
data/embeddings/embedding-manifest.json
data/queries/queries.jsonl                  질의 정의(필터 포함)
```

`document-vectors.jsonl` 한 줄:

```json
{"id":"chunk-000-00","documentId":"doc-000","chunkId":"chunk-000-00","content":"...",
 "metadata":{"tenant_id":"alpha","status":"active","category":"spring-tx","language":"ko","tags":["spring-tx"]},
 "embedding":[0.021,-0.118, ...]}
```

원본의 플랫 필드 중 `id`/`documentId`/`chunkId`/`content`/`embedding`을 제외한 나머지가
전부 `metadata`로 들어갑니다. 필터는 이 `metadata` 하위 키를 대상으로 합니다.

## metadata와 필터

질의 300개 중 30개(10%)가 `filter`를 가집니다.

```json
{"queryId":"q-001","query":"... alpha 테넌트의 active 문서만 찾아줘",
 "filter":{"tenant_id":"alpha","status":"active"}}
```

필터가 있는 질의와 없는 질의는 DB 내부에서 완전히 다른 경로를 탑니다.
그래서 결과도 분리해서 기록합니다. [../03-benchmark-design/metrics.md](../03-benchmark-design/metrics.md)를 봅니다.

## 왜 embedding 시간을 측정하지 않는가

embedding은 Vector DB의 성능이 아니라 모델과 GPU의 성능입니다.
같은 벡터 파일을 다섯 DB에 재사용하면 그 변수가 완전히 제거됩니다.

## 관련 문서

- [vector-and-dimension.md](vector-and-dimension.md)
- [../03-benchmark-design/dataset-and-queryset.md](../03-benchmark-design/dataset-and-queryset.md)
