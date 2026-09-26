# Vector와 Dimension

## Vector

텍스트를 숫자 배열로 바꾼 것이 embedding vector입니다.
의미가 비슷한 문장은 벡터 공간에서 가까운 위치에 놓입니다.

```text
"Spring Transaction 지연시간"  →  [0.021, -0.118, 0.073, ... ]
```

## Dimension

배열의 길이가 dimension입니다. 이 프로젝트는 **1024차원**을 사용합니다.
BGE-M3의 dense embedding 차원입니다.

차원은 실험 전체를 통틀어 같아야 합니다. 다르면 비교 자체가 성립하지 않습니다.

- 차원이 크면 표현력이 늘지만 저장 공간과 거리 계산 비용이 함께 늡니다.
- 1024차원 float 하나는 4 KiB입니다. 10,000건이면 벡터만 약 39 MiB입니다.

## 정규화

BGE-M3는 L2 정규화된 벡터를 출력합니다. `embedding-manifest.json`이 실제 노름을 기록합니다.

```json
"minL2Norm" : 0.9999989010731741,
"maxL2Norm" : 1.0000009606140923
```

모든 벡터의 노름이 1에 가까우므로 cosine과 dot product의 순위가 사실상 같습니다.
그럼에도 전 DB에서 metric을 cosine으로 통일합니다.

## 이 프로젝트에서 고정하는 것

| 항목 | 값 | 고정 방법 |
|---|---|---|
| 모델 | `bge-m3:latest` (Ollama) | manifest의 model digest |
| 차원 | 1024 | `BenchmarkRunner.validateDimensions()`가 불일치 시 실패 |
| 벡터 값 | 한 번 생성 후 재사용 | 입력·출력 SHA-256을 manifest에 기록 |
| distance metric | cosine | 스토어 metric과 다르면 실행 거부 |

벡터를 다시 만들면 이전 결과와 비교할 수 없습니다.
`generateEmbeddings`는 완성된 출력이 있으면 manifest의 입력·모델·출력 provenance와 해시·레코드 수 등을 검증한 뒤 재사용합니다. 출처를 입증하지 못하는 출력은 건수만 맞는다고 재사용하지 않습니다. 현재 실측은 고정된 10k·1024차원이며 프로젝트의 1k~10k 범위 전체를 측정했다는 뜻은 아닙니다.

## 관련 문서

- [chunk-and-embedding.md](chunk-and-embedding.md)
- [similarity-and-topk.md](similarity-and-topk.md)
