# Controlled Variables

현재 실측 기준은 [fairness-v2의 620점](../07-results/fairness-v2-results-20260913.md)입니다. 프로젝트 예상 규모 1k~10k 중 합성 10k를 측정했습니다.

## 고정·검증 항목

| 변수 | 값 | 강제 방법 |
|---|---|---|
| embedding | 같은 BGE-M3 1024차원 JSONL | 입력 SHA-256 기록 |
| source of truth | 같은 document/chunk snapshot | PostgreSQL 동기화 및 hash/count 검증 |
| metric | cosine | store와 불일치 시 거부 |
| topK | 10 | matrix runner |
| 품질 참고선 | Recall 0.90, 0.95 | 탐색 산포도 수평선; 이 문서의 구성 탈락 기준 아님 |
| query split | calibration 100 / evaluation 200 | query-type 층화 SHA-256 |
| 부하 | concurrency 10, 워밍업 3~10개 pass, measurementIterations 5 | 워밍업 최근 3개 p95 범위/median ≤15%; 미충족도 기록 |
| 측정 증거 | 최소 30초 및 검색 구간 내 자원 표본 30개 | 부족 시 완전한 query batch 단위로 연장 |
| 반복 | 이번 실행 전체 재구축 5회 | buildId·runNumber 기록 |
| 자원 | 대상 합계 4 vCPU / 8GiB / swap 없음 | `docker inspect` 불일치 시 중단 |

인덱스 생성 파라미터는 결과의 `index_parameters`, 검색 폭은 `search_parameters`에 기록합니다. 서로 다른 계열에 존재하지 않는 파라미터를 억지로 같게 만들지 않습니다.

이번 완료 실행은 독립 재구축 70회·620개 점이며 동일 입력 해시·부하 조건과 점별 30초/30표본을 확인했습니다. DB 순서를 회차별로 교차하고 구성 안 검색 파라미터도 runNumber 시드로 섞습니다. 이 조치가 호스트 캐시·JIT·열 상태를 완전히 제거하는 것은 아닙니다. [과거 372점](../07-results/sweep-results-20260911.md)의 3회 재구축·최소 5초·검색 폭 오름차순 실행은 별도 조건입니다.

## 버전

| 구성 | 기본 버전 |
|---|---:|
| Java / Spring Boot | 21 / 4.1.1 |
| PostgreSQL / pgvector | 17 / 0.8.6 |
| Qdrant | 1.19.0 |
| Weaviate | 1.39.3 |
| Milvus | 3.0.1 |
| OpenSearch | 3.8.0 |

OpenSearch JVector는 공식 JVector plugin을 설치한 별도 이미지에서 실행합니다.

## 자원 예산

| 대상 | 컨테이너별 배분 | 합계 |
|---|---|---|
| pgvector/Qdrant/Weaviate/OpenSearch | 대상 단일 컨테이너 4 vCPU/8GiB | 4 vCPU/8GiB |
| Milvus | 본체 3 vCPU/6656MiB, etcd 0.5/512MiB, MinIO 0.5/1GiB | 4 vCPU/8GiB |

모든 컨테이너의 memory와 memory+swap limit를 같게 둡니다. PostgreSQL은 외부 DB 프로필에서 원본 저장소로 함께 뜨지만 검색 대상 자원 합계에서는 제외합니다.

OpenSearch의 `-Xms4g -Xmx4g`는 **위 8GiB 컨테이너 예산 안의 4GiB JVM 힙 설정**입니다. RAM Avg/Max는 Docker 관측치이고 힙 크기·컨테이너 한도·서비스 허용량과 같은 값이 아닙니다. RAM 2GiB와 p95 30ms는 합의되지 않은 `DecisionGate` 기본값이므로 이번 산포도 분석의 탈락 조건으로 쓰지 않습니다. 코드에서 제거됐다는 뜻은 아닙니다.

## 남는 환경 변수

클라이언트 JVM 자원, JDBC/JSON/GraphQL 직렬화 차이, Windows Docker Desktop 포트포워드, OpenSearch JVM heap 선점은 동일화하지 못합니다. 따라서 결과는 알고리즘 microbenchmark가 아니라 이 애플리케이션 검색 경로의 end-to-end 지표입니다.
