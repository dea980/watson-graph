# Tabular text-to-SQL (DuckDB, watsonx.data 라이크하우스 경로)

표 데이터는 벡터 RAG로 풀지 않는다. 비정형 텍스트는 RAG(`/api/rag`), 정형 표는 SQL(`/api/tabular`)로 갈라 처리한다.

## 1. 왜 RAG가 아니라 SQL인가

Tika가 CSV/xlsx를 텍스트로 평탄화하면 행이 열 헤더와 분리된다. "강동구에 몇 채?", "위험 소행성 개수?", "평균 보증금?" 같은 집계·필터·카운트는 벡터 유사도로 원천적으로 못 한다(임베딩은 "비슷함"이지 "정확히 세기"가 아니다). 표의 정밀 조회는 **구조적 질의(SQL)**가 정답이다.

IBM 맥락에서 이게 바로 **watsonx.data 라이크하우스** 패턴이다: 데이터를 옮기지 않고(zero-ETL) 컬럼 엔진으로 파일 위에서 SQL을 돌린다.

## 2. 왜 DuckDB인가 (Postgres/H2 두고)

이미 prod에 Postgres가 있는데 굳이 DuckDB를 더한 이유:

| | Postgres(기존) | H2(dev/demo) | DuckDB |
|---|---|---|---|
| 프로필 | prod 전용 | dev/demo | 임베디드, 전 프로필 |
| 파일 적재 | COPY로 테이블 적재(ETL) | CSVREAD | `read_csv_auto`로 파일 직접 질의(zero-ETL) |
| 엔진 | OLTP(행) | OLTP(행) | OLAP(컬럼) — 표 스캔/집계가 본업 |
| Parquet | 비기본 | 아니오 | 네이티브 — `articles.parquet`도 SQL 가능 |

핵심: Postgres는 dev/demo엔 없고(거기선 H2), 파일마다 ETL이 필요하며 OLTP다. DuckDB는 임베디드 컬럼 엔진이라 모든 프로필에서 파일을 그 자리에서 집계 질의하고, 이미 쓰는 Parquet과 직결된다 — "라이크하우스 위 SQL" 레슨을 정확히 보여준다.

## 3. 구성

```
POST /api/tabular/load?table=t&path=sample/x.csv
   -> TabularSqlService.registerCsv: CREATE TABLE t AS SELECT * FROM read_csv_auto('x.csv')

POST /api/tabular/ask {table, question}
   -> TextToSqlService.ask:
        schema = DESCRIBE t
        sql = LLM(question + schema)        (OllamaService.ask -> granite4, query_log 감사됨)
        rows = TabularSqlService.runSelect(sql)   (SELECT 전용)
   -> {sql, columns, rows}
```

- **TabularSqlService** — DuckDB in-memory 연결(`jdbc:duckdb:`), CSV 등록, `DESCRIBE` 스키마, SELECT 실행. 드라이버는 JDBC SPI로 자동 등록(`Class.forName` 불필요).
- **TextToSqlService** — 질문+스키마+샘플행을 프롬프트로 LLM에 주고 SQL을 받아 코드펜스/설명을 벗겨 실행. 생성된 SQL을 응답에 함께 돌려줘(투명성).
- **TabularController** — `/load`, `/ask`.

설계 결정 — **적재 시 컬럼명 정규화(`read_csv_auto(..., normalize_names=true)`)**: `"Orbiting Body"` 같은 공백·특수문자 컬럼을 `orbiting_body`로 바꿔 등록한다. 작은 LLM(granite4)은 "공백 컬럼은 큰따옴표로 인용" 프롬프트 지시를 매번 못 지켜 SQL이 깨졌는데(예: `SELECT Orbiting Body` 파싱 에러), 식별자를 적재 단계에서 깔끔하게 만들면 LLM이 인용할 일이 아예 없어진다. **LLM을 설득하기보다 데이터를 LLM-친화적으로 만들어 오류를 원천 차단** — 결정적이고 일관됨. (프롬프트의 인용 지시와 샘플행 제공은 보조 방어로 함께 둔다.)

## 4. 보안

- 실행은 **SELECT/WITH만** 허용, `DROP/DELETE/UPDATE/INSERT/ALTER/CREATE/ATTACH/COPY/PRAGMA/INSTALL/LOAD` 차단, 최대 100행.
- 테이블명은 `[A-Za-z_][A-Za-z0-9_]*` 정규식 검증(인젝션 방지).
- `path`는 데모라 서버측 `sample/`만 가정. 운영이면 경로 화이트리스트로 path traversal 차단 필요.
- LLM이 만든 SQL은 신뢰 경계 밖이다 — 가드(SELECT 전용)가 1차 방어, 읽기 전용 연결/권한이 2차 방어가 되어야 한다.

## 5. 예시

```bash
curl -s -X POST "localhost:8080/api/tabular/load?table=revenue&path=sample/quarterly-revenue-2025.csv"
curl -s -X POST localhost:8080/api/tabular/ask -H 'Content-Type: application/json' \
  -d '{"table":"revenue","question":"What was the Q3 revenue and the average yoy growth?"}'

curl -s -X POST "localhost:8080/api/tabular/load?table=nasa&path=sample/nasa.csv"
curl -s -X POST localhost:8080/api/tabular/ask -H 'Content-Type: application/json' \
  -d '{"table":"nasa","question":"How many objects are flagged hazardous?"}'
```

nasa.csv(4687행)의 "위험 몇 개?"는 `SELECT COUNT(*) FROM nasa WHERE Hazardous = true` — 벡터 RAG는 못 세지만 SQL은 정확히 센다. 이게 정형/비정형 분기의 핵심 시연이다.

## 6. 한계와 다음 단계

- **xlsx**: 지원됨. `registerXlsx`가 Apache POI로 시트를 읽어(오프라인, DuckDB excel 확장 불필요) 임시 CSV로 변환 후 `registerCsv`에 합류한다. `/load?...&headerRow=N`으로 제목·안내행을 skip한다 — 정부/기업 양식은 진짜 헤더가 위 몇 줄 아래에 있기 때문(예: 주택목록.xlsx는 `headerRow=6`). 단 병합셀·다단 헤더는 컬럼명이 거칠어질 수 있어, 셀 단위보다 COUNT/GROUP BY 같은 집계가 견고하다.
- **테이블 영속**: 현재 in-memory(재시작 시 사라짐). 파일은 sample/에 있으니 `/load`로 다시 등록하면 됨. 영속이 필요하면 DuckDB file DB(`jdbc:duckdb:./data/tabular.db`).
- **라우팅 자동화**: 지금은 호출자가 RAG vs SQL을 고른다. 다음은 질문 유형(집계/필터 키워드, 표 namespace)을 보고 자동 라우팅.
- **Parquet 질의**: `read_parquet('data/articles.parquet')`로 article 저장소를 SQL로 들여다보는 거버넌스/분석 뷰.

비정형은 [EVALUATION.md](EVALUATION.md)의 RAG 평가로, 정형은 이 SQL 경로로 — 표를 임베딩에 욱여넣지 않는 게 핵심 설계 원칙이다([INGESTION-FORMATS.md](INGESTION-FORMATS.md)).
