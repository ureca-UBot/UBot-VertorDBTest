# Benchmark Results — 1차 (2026-09-11)

> **현재 기준 안내 (2026-09-13):** 최신 결과는 [fairness-v2 620개 측정·6축 산포도](fairness-v2-results-20260913.md)다. 아래는 2026-09-11 당시 실행·해석을 보존한 역사 자료이며 수치와 판정 표현을 최신 결과에 적용하지 않는다. 채점·측정·어댑터 조건이 달라 최신 620개와 합산하지 않는다.

> **역사 자료:** calibration/evaluation 분리, 3~5회 전체 재구축, 순서 교차, 순수 검색 QPS,
> T01~T28 다중 인덱스 행렬을 적용하기 전 결과입니다. 현재 제품 순위표로 사용하지 않습니다.

> **상태: 무효. 이 실행의 지연 수치는 DB 선정 근거로 쓰지 않는다.**
> 결함 수정 후 재실행은 완료했으며 결과는
> [benchmark-results-rerun.md](benchmark-results-rerun.md)에 있다.
>
> 측정 후 세 가지 결함을 확인했다. Qdrant payload index 누락으로 필터 질의가 full scan이 됐고,
> 필터 질의 10%가 합산 p95·p99를 지배했으며 전체 Recall과 무필터 latency의 비교 모집단도
> 일치하지 않았다.
> 따라서 **아래 판정 1번과 3번은 성립하지 않는다.**
>
> 근거와 수정 내용은 [analysis.md](analysis.md)에 있다.
> 아래 내용은 수정 전 실행 기록으로만 남겨둔다. Recall 수치는 당시 전체 질의 혼합의 관측값이지만
> 무필터 ANN 목표 판정과 직접 비교에는 사용하지 않는다.

## 실행 조건

- 입력: BGE-M3 dense 1024차원, 문서 10,000건, 질의 300건
- 모델: Ollama `bge-m3:latest`
- 모델 digest: `7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab`
- 거리 함수: cosine, Top-K: 10
- 주요 Target Recall@10: 0.80 / 0.90 / 0.95, 허용오차 ±0.01
- 부하: concurrency 10, warm-up 1회, 측정 5회(각 DB·목표별 1,500 search)
- 인덱스: 모든 DB HNSW
- Ground Truth: 동일 필터를 적용한 Java brute-force exact top-10
- Vector DB 배포 예산: 각 대상 합계 4 vCPU / 8 GiB
- 원시 결과: `benchmark-result/production-main-recall-final`

단일 컨테이너 DB에는 예산 전부를 적용했다. Milvus는 본체 3 vCPU/6656 MiB, etcd 0.5 vCPU/512 MiB, MinIO 0.5 vCPU/1 GiB로 나눠 합계를 동일하게 맞췄다. 모든 컨테이너는 메모리와 swap 합계 상한을 같은 값으로 설정해 swap을 사용할 수 없다. 실행기는 측정 전에 `docker inspect`의 실제 CPU·메모리·swap 합계를 검증하며 다르면 즉시 중단한다. 전용 Vector DB 프로필에서도 기동되는 PostgreSQL은 공통 원본 저장소이고 검색 요청 경로와 리소스 측정 대상에서는 제외했다.

문서 벡터 SHA-256은 `cc23f095b88585453940e8fca998f8b16f9573361b0c83f021d7bb095ba9e4c6`, 질의 벡터 SHA-256은 `d06cc81c1169e1a926fc69a835fabda560932d8834bd687893fe0da301c4cfa9`다.

## 검색 결과

| DB | 목표 | 실제 | 범위 충족 | 선택 방식 | 검색 파라미터 | p95 ms | p99 ms | QPS | CPU % | RAM MiB |
|---|---:|---:|:---:|---|---:|---:|---:|---:|---:|---:|
| pgvector | 0.80 | 0.8240 | X | CLOSEST_AVAILABLE | ef_search=80 | 5.56 | 7.16 | 2,914.68 | 0.01* | 164.1 |
| pgvector | 0.90 | 0.9137 | X | CLOSEST_AVAILABLE | ef_search=200 | 9.79 | 25.65 | 1,979.86 | 0.02* | 164.2 |
| pgvector | 0.95 | 0.9450 | O | WITHIN_TOLERANCE | ef_search=400 | 34.07 | 39.77 | 1,333.85 | 42.25 | 165.8 |
| Qdrant | 0.80 | 0.7943 | O | WITHIN_TOLERANCE | hnsw_ef=80 | 130.74 | 182.82 | 630.88 | 131.64 | 97.4 |
| Qdrant | 0.90 | 0.9170 | X | CLOSEST_AVAILABLE | hnsw_ef=400 | 104.25 | 195.06 | 539.49 | 131.91 | 98.6 |
| Qdrant | 0.95 | 0.9417 | O | WITHIN_TOLERANCE | hnsw_ef=1000 | 126.64 | 284.93 | 420.01 | 213.31 | 97.1 |
| Weaviate | 0.80 | 0.8067 | O | WITHIN_TOLERANCE | ef=40 | 29.73 | 38.83 | 696.84 | 191.66 | 422.9 |
| Weaviate | 0.90 | 0.9143 | X | CLOSEST_AVAILABLE | ef=200 | 29.86 | 39.25 | 655.65 | 148.09 | 426.1 |
| Weaviate | 0.95 | 0.9553 | O | WITHIN_TOLERANCE | ef=400 | 27.88 | 34.29 | 653.62 | 128.32 | 235.4 |
| Milvus | 0.80 | 0.9030 | X | CLOSEST_AVAILABLE | ef=40 | 28.34 | 34.90 | 1,385.07 | 39.09 | 563.2 |
| Milvus | 0.90 | 0.9030 | O | CLOSEST_AVAILABLE** | ef=40 | 23.11 | 39.67 | 1,346.61 | 35.38 | 547.3 |
| Milvus | 0.95 | 0.9583 | O | WITHIN_TOLERANCE | ef=80 | 26.88 | 37.32 | 1,267.64 | 64.08 | 543.6 |
| OpenSearch | 0.80 | 0.9303 | X | CLOSEST_AVAILABLE | ef_search=10 | 6.97 | 30.85 | 2,041.59 | 0.55* | 4,835.3 |
| OpenSearch | 0.90 | 0.9303 | X | CLOSEST_AVAILABLE | ef_search=10 | 5.85 | 8.22 | 2,510.14 | 0.67* | 4,835.3 |
| OpenSearch | 0.95 | 0.9413 | O | WITHIN_TOLERANCE | ef_search=20 | 6.41 | 16.06 | 2,333.45 | 0.70* | 4,835.3 |

`CLOSEST_AVAILABLE`은 후보 `[10,20,40,80,120,200,400,800,1000]` 중 튜닝 5회 평균 Recall이 목표 범위에 없어서 가장 가까운 값을 선택했다는 의미다. 실제 Recall은 별도의 본 측정 5회 평균이다.

`*` 전체 검색 측정이 Docker CPU의 약 1초 집계창보다 짧은 행은 CPU 평균이 과소 집계될 수 있어 CPU 효율 비교에서 제외한다. `**` Milvus 0.90은 튜닝 Recall 0.9130으로 범위를 벗어나 closest로 선택됐지만 별도 본 측정은 0.9030으로 범위에 들어왔다.

## 적재 및 검색 준비 시간

| DB | Time-to-ready ms | Upsert ms | 보고된 index size MiB |
|---|---:|---:|---:|
| pgvector | 12,642 | 12,595 | 47.1 |
| Qdrant | 5,143 | 3,891 | 미지원 |
| Weaviate | 8,886 | 8,625 | 미지원 |
| Milvus | 8,903 | 4,130 | 미지원 |
| OpenSearch | 42,320 | 41,933 | 41.2 |

`Time-to-ready`는 drop/create부터 적재 및 비동기 인덱싱 완료까지의 전체 시간이다. 각 제품의 순수 index build API가 같지 않으므로 이 값은 운영 관점의 준비 시간으로만 비교한다.

## 판정

1. Recall 0.80 범위를 실제로 충족한 Qdrant와 Weaviate만 직접 비교할 수 있다. 이 중 Weaviate가 p95 29.73 ms로 Qdrant 130.74 ms보다 낮았다.
2. Recall 0.90 범위를 본 측정에서 충족한 것은 Milvus뿐이다. 나머지는 closest 결과이며 같은 Recall 성능 비교에 섞으면 안 된다.
3. Recall 0.95는 다섯 DB 모두 허용 범위를 충족했다. OpenSearch가 p95 6.41 ms와 QPS 2,333.45로 가장 빨랐지만 약 4.7 GiB RAM을 사용했다. pgvector는 p95 34.07 ms, QPS 1,333.85, RAM 165.8 MiB였다.
4. 자동 튜닝이 후보 전체에 warm-up과 5회 반복을 수행한 뒤 본 측정을 실행하므로 이 결과는 warm-cache 조건이다. cold-cache 결과와 직접 섞지 않는다.
5. 이 결과는 리소스를 통제한 단일 인덱스 빌드다. 최종 선정 전에는 최소 3회 재구축 반복, 실행 순서 교차, 실제 운영 필터 선택도와 데이터 크기에서 재검증해야 한다.

0.70과 0.99는 주 비교표에서 제외했다. 필요하면 `data/benchmark-request-auxiliary.json`으로 별도 result directory에 실행한다.
