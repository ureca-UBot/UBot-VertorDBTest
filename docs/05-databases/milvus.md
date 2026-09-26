# Milvus

Native engine에서 HNSW, IVF_FLAT, IVF_SQ8, IVF_PQ, DISKANN(T11, T13, T15, T17, T19)을 측정합니다. standalone 본체·etcd·MinIO 합계에 4 vCPU/8GiB 제한을 적용합니다.

## 인덱스와 검색 파라미터

| Index | Build | Search |
|---|---|---|
| HNSW | M=16, efConstruction=128 | ef |
| IVF_FLAT | nlist=128 | nprobe |
| IVF_SQ8 | nlist=128 | nprobe |
| IVF_PQ | nlist=128, m=64, nbits=8 | nprobe |
| DISKANN | server default build params | search_list |

IVF_PQ의 `m=64`, `nbits=8`은 1,024차원을 64개 부분으로 나누고 부분마다 1바이트의 코드를 사용합니다. PQ 코드만 비교하면 float32 원본 벡터 4,096바이트 대비 64바이트지만, 코드북·ID·엔진·보조 서비스까지 포함한 실제 RAM이 64배 줄어든다는 뜻은 아닙니다.

최신 v2의 IVF_PQ 전체 탐색 그리드에서 혼합 Recall은 0.494330–0.613918이었습니다. 이는 현재 `m=64`, `nbits=8`, refine 미사용 설정의 관측이며 제품 전체의 품질 상한이나 압축만의 인과 효과를 입증하지 않습니다. 낮은 Recall의 45개 점도 전부 보존합니다.

IVF_PQ는 dimension이 `m`으로 나누어떨어지지 않으면 실행을 거부합니다. DISKANN을 위해 `docker/milvus/user.yaml`의 `queryNode.enableDisk: true`를 container config overlay로 mount합니다.

## 준비 장벽

insert 뒤 `collections/flush`와 비동기 `collections/load`를 호출합니다. `indexes/describe`의 `indexState=Finished`, indexed rows 전체 이상, pending rows 0과 `get_load_state`의 `LoadStateLoaded`, progress 100%를 확인합니다. 공식 SDK `getQuerySegmentInfo`에도 기대 row 전체와 index name을 가진 Sealed/Flushed segment가 나타날 때까지 기다립니다. 다만 준비 시점의 장벽이 이후 모든 검색 중 동일 상태를 보장하지는 않습니다. 최신 사후 검토에서 일부 측정 상태 로그의 pending rows와 본 측정 중 Recall 변동을 확인했으므로 이 장벽으로 문제가 완전히 해결됐다고 쓰지 않습니다.

## drift 진단

과거 단일 실행에서 calibration 0.9578 → 본 측정 0.7422가 관측됐으므로 Milvus는 자동 stability audit 대상입니다. 각 결과 행에서 해당 측정의 고정 파라미터와 calibration 무필터 query를 사용해 serial 3회와 concurrency 10의 3회를 추가 실행합니다.

과거 스모크에서는 REST index/load 상태 완료 뒤 query node의 segment 목록이 비어 있던 준비 간극을 확인하고 SDK segment 장벽을 추가했습니다. 당시 T12 등의 수치는 [과거 adapter smoke](../07-results/adapter-smoke-20260911.md)에 보존합니다. 현재 결과는 전체 sweep의 T11/T13/T15/T17/T19이며, 각 행의 별도 진단값을 함께 읽습니다.

공식 Java SDK의 `getQuerySegmentInfo`로 segment ID/state/rows/memory/index/node 정보를 받고 REST로 상태를 저장합니다. 첫 concurrent 진단 표본 대비 최대 편차나 serial/concurrent 중앙값 차이가 기본 0.05를 넘거나 상태 수집 오류가 있으면 stability_verified=false를 기록합니다. 현재 Recall 진단 검색은 **본 측정 뒤에만** 수행하고 상태 판정도 오류 키 중심이라 pending rows·segment 변화 자체를 충분히 검증하지 못합니다. 따라서 `verified=true`가 본 측정 중 안정성을 보증하지 않습니다.

최신 결과에서 DISKANN 5개 측정은 같은 질의의 Recall 변동을 query audit으로 확인했는데도 `verified=true`였습니다. 이 5개는 산포도에서 경고를 붙이고 원시값을 보존하되 확정 선정 근거는 보류합니다. 코드의 진단 결함이 이미 수정됐다고 해석하지 않습니다. 해당 run/검색 설정은 [최신 결과 보고서](../07-results/fairness-v2-results-20260913.md)에 정리합니다.

진단 검색은 최종 QPS/latency timer 밖입니다.

## 필터와 크기

metadata는 JSON field 표현식으로 필터합니다. JSON path index는 주 matrix에 포함하지 않았습니다. 순수 per-index size를 REST 결과에서 분리하지 않으므로 `index_size_bytes=-1`입니다.

## 최신 fairness-v2 실측 — 2026-09-13 완료

[최신 620개 결과](../07-results/fairness-v2-results-20260913.md) 중 이 DB의 실측입니다. 범위는 **모든 검색 파라미터와 다섯 재구축 회차**의 혼합 지표입니다. 최소 p95와 최대 Recall이 같은 점이라는 뜻은 아닙니다. 합성 10k·1024차원·동시성 10 조건이며 DISKANN 경고점도 범위에 포함합니다.

| 구성 | 실제 검색 그리드 | 점 수 | Recall@10 범위 | 전체 p95 ms 범위 |
|---|---|---:|---:|---:|
| T11 / Native / HNSW | ef: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 45 | 0.747423–0.999485 | 11.560–45.096 |
| T13 / Native / IVF_FLAT | nprobe: 1, 2, 4, 8, 16, 32, 64, 96, 128 | 45 | 0.712371–1.000000 | 11.267–61.805 |
| T15 / Native / IVF_SQ8 | nprobe: 1, 2, 4, 8, 16, 32, 64, 96, 128 | 45 | 0.710825–0.995876 | 11.239–44.804 |
| T17 / Native / IVF_PQ | nprobe: 1, 2, 4, 8, 16, 32, 64, 96, 128 | 45 | 0.494330–0.613918 | 13.942–33.622 |
| T19 / Native / DISKANN | search_list: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 45 | 0.728535–0.995361 | 15.220–98.858 |

225개 중 워밍업 미달 114개와 위 DISKANN 변동 5개를 표시한 채 [최신 다축 산포도](../07-results/fairness-v2-results-20260913.md)에서 같은 Recall 수준의 설정끼리 살펴봅니다. 경고 사유는 중복될 수 있으며 낮은 Recall 자체는 검색 장애가 아닙니다. [2026-09-11 v1 결과](../07-results/sweep-results-20260911.md)는 역사 자료입니다.

## 참고

- [Milvus disk index](https://milvus.io/docs/disk_index.md)
- [Milvus query node disk 설정](https://milvus.io/docs/configure_querynode.md)
- [Java SDK getQuerySegmentInfo](https://milvus.io/api-reference/java/v3.0.x/v2/Management/getQuerySegmentInfo.md)
