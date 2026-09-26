# Exact Search와 ANN

## Exact Search

모든 문서와 거리를 계산해 진짜 Top-K를 찾습니다. 항상 정확하지만 문서 수에 비례해 느려집니다.

10,000건 × 1024차원이면 질의 하나에 약 1,000만 번의 곱셈이 필요합니다.
100만 건이면 100배가 됩니다.

## ANN (Approximate Nearest Neighbor)

인덱스를 만들어 후보를 좁힌 뒤 그 안에서만 비교합니다. 훨씬 빠르지만 **정답을 놓칠 수 있습니다.**

```text
Exact:  10,000건 전부 비교          → 느림, Recall = 1.0
ANN:    후보를 좁혀 비교            → 비용 감소 가능, Recall ≤ 1.0
```

## 핵심 트레이드오프

탐색 폭을 줄이면 검색 비용이 감소하는 경향이 있지만, 개별 측정의 latency와 Recall이 항상 단조롭게 변하는 것은 아닙니다. 속도와 실제 Recall을 함께 기록해야 합니다.

```text
탐색 폭 ↓  →  latency ↓, Recall ↓
탐색 폭 ↑  →  latency ↑, Recall ↑
```

이 프로젝트는 각 DB·인덱스의 검색 파라미터 그리드를 전부 측정한 뒤 **X=p95 latency, Y=Recall@10** 산포도에서 비교합니다. Recall 0.90과 0.95는 수평 참고선이며 목표 범위에 맞추는 자동 튜닝이나 합격 판정을 수행하지 않습니다.

이는 탐색 실행과 결과 해석의 설명입니다. 현재 [fairness-v2 결과](../07-results/fairness-v2-results-20260913.md)는 124개 설정 × 독립 재구축 5회인 **620점**이며, 코드의 미합의 기본 SLO 판정은 구성 탈락에 사용하지 않습니다. Recall이 비슷한 점끼리 비용과 반복 퍼짐을 읽습니다.

[2026-09-11 sweep 실험](../07-results/sweep-results-20260911.md)은 14개 구성의 124개 파라미터를 세 번씩 측정한 372개 점을 모두 보존했습니다. 참고선보다 낮거나 높은 점, 탐색 폭이 늘어도 Recall이 내려간 점도 실제 관측입니다.

이 372점은 v1 역사 실험입니다. v2는 동점 채점·측정 시간·응답 조건이 달라 둘을 합산하지 않습니다. 프로젝트 예상 규모는 1k~10k이며 합성 10k만 완료했습니다. 작은 데이터의 ANN 이점과 실제 운영 필요성은 1k·실서비스 입력에서 별도로 확인해야 합니다.

## 이 프로젝트에서 Exact Search의 역할

Exact Search는 비교 대상이 아니라 **채점 기준**입니다.

```text
Java ExactSearchEngine  →  Exact Top-10  →  Ground Truth
                                              ↓
                          DB의 ANN Top-10과 비교  →  Recall@10
```

DB가 제공하는 exact 모드를 쓰지 않고 Java brute-force로 직접 계산합니다.
DB의 exact 구현에 버그가 있어도 채점 기준은 오염되지 않습니다.

자세한 내용은 [../03-benchmark-design/ground-truth.md](../03-benchmark-design/ground-truth.md)를 봅니다.

## 언제 ANN이 필요 없는가

문서 수가 적으면 exact가 충분히 빠릅니다. 실제로 Qdrant는 컬렉션이 임계값보다 작으면
의도적으로 exact scan을 씁니다. 그러면 HNSW를 측정하려던 실험이 exact를 측정하게 됩니다.

그래서 이 프로젝트는 임계값을 낮춰 강제로 인덱스를 만들게 합니다.

```yaml
vector.qdrant.full-scan-threshold: 10   # KB
vector.qdrant.indexing-threshold: 10    # KB
```

같은 이유로 pgvector는 검색 세션에 `enable_seqscan=off`를 설정합니다.

## 관련 문서

- [hnsw.md](hnsw.md)
- [recall.md](recall.md)
