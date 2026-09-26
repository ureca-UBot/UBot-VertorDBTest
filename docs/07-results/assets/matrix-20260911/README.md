# 2026-09-11 T01–T28 문서 근거 자료

> **현재 기준 안내 (2026-09-13):** 최신 결과는 [fairness-v2 620개 측정·6축 산포도](../../fairness-v2-results-20260913.md)다. 아래는 2026-09-11 당시 실행·해석을 보존한 역사 자료이며 수치와 판정 표현을 최신 결과에 적용하지 않는다. 채점·측정·어댑터 조건이 달라 최신 620개와 합산하지 않는다.

[전체 보고서](../../matrix-results-20260911.md)와 [84회 실행 기록](../../matrix-runs-20260911.md)에 사용한 자료다.

- `vector-db-result.csv`: 실행 원본 84행의 바이트 단위 동일 사본. 지표·필터 부분집합·자원·검색 파라미터·환경 정보가 포함된다.
- `scatter-all-84.png`, `scatter-all-84.svg`: 84점 전체 산포도. 케이스별 색상·회차별 기호·개별 이름을 표시한다.
- `scatter-all-84-manifest.json`: 그림 원본 CSV의 해시, 84개 고유 라벨, 좌표 변경·집계·제품 선정 필터 미적용 기록. 내부 절대경로는 최초 생성 위치다.
- `reproduction.json`: 실행·테스트 집계, 입력과 결과 해시, 질의 분할, 이 디렉터리 근거 파일의 해시.
- `query-membership.json`, `ground-truth-top10.jsonl`: 문서 작성 시 기존 분할 알고리즘을 재현해 기록된 두 분할 해시와 일치함을 확인한 질의 소속·필터 유무·정답 수, 실행 원본 정답 ID 사본. 정답 없는 질의가 필터 Recall에 포함되는 정도를 확인하는 근거다.
- `benchmark-matrix.json`, `embedding-manifest.json`, `docker-images.json`, `run-protocol.json`, `run-status.json`: 실행 당시 보존한 설정과 식별자 사본. protocol의 기존 decision 임계값은 당시 스크립트 설정 기록이며 문서의 선정 기준이 아니다.
- `working-tree.patch`, `PgVectorIndexManagerTest.java`, `source-sha256.json`, `test-results.json`: 실행 전 IVFFlat 수정과 테스트 근거. 새 테스트는 당시 Git 미추적 파일이어서 patch 외에 별도로 보존했다.

벡터 본문, 실제 실행 JAR, 42개 benchmark raw JSON과 로그 전체는 이 문서 자료 디렉터리에 복제하지 않았다. 원래 로컬 `benchmark-result/matrix-t01-t28-20260911-183751/`에 보존되어 있다. 이 폴더의 자료는 기록을 읽고 집계하는 데 필요한 근거이며, 이 폴더만으로 DB 실행 환경 전체가 복원되는 것은 아니다.
