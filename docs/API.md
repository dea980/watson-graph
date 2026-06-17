# MiniWatson — REST API Reference

> Base URL: `http://localhost:8080`
> Content-Type: `application/json` (별도 명시 없으면)
> Live interactive docs: `http://localhost:8080/swagger-ui.html` (Swagger 설정 후)

---

## 목차

1. [RAG (Retrieval-Augmented Generation)](#1-rag)
   - `POST /api/rag/ask`
   - `GET  /api/rag/models`
2. [Data](#2-data)
   - `POST   /api/data/ingest`
   - `POST   /api/data/ingest-batch`
   - `POST   /api/data/ingest-file`
   - `POST   /api/data/summarize/{id}`
   - `GET    /api/data/articles`
   - `GET    /api/data/documents`
   - `GET    /api/data/count`
   - `GET    /api/data/index/stats`
   - `DELETE /api/data/documents`
   - `DELETE /api/data/articles/{id}`
3. [Governance](#3-governance)
   - `GET  /api/governance/logs`
   - `GET  /api/governance/stats`
   - `POST /api/governance/feedback`
4. [Multimodal](#4-multimodal)
   - `POST /api/multimodal/ask`
   - `POST /api/multimodal/ingest`
5. [Tabular (text-to-SQL)](#5-tabular-text-to-sql)
   - `POST /api/tabular/load`
   - `POST /api/tabular/ask`
6. [Health](#6-health)

---

## 1. RAG

Controller: `RagController` — `@RequestMapping("/api/rag")`.

### `POST /api/rag/ask`

질문 → embedding → retrieval (top-K cosine + optional hybrid/rerank) → augmented prompt → chat model → answer + sources.

**Request**
```bash
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "What is RAG?"}'
```

**Request Body** (`AskRequest`)
```json
{
  "question": "What is RAG?",
  "namespace": "default",
  "model": "ibm/granite4:latest",
  "rerank": "mmr",
  "hybrid": true
}
```

| Field     | Type    | Required | Description                                                        |
|-----------|---------|----------|--------------------------------------------------------------------|
| question  | string  | Yes      | User question.                                                     |
| namespace | string  | No       | Tenant/collection to search. Defaults to `"default"` if omitted.  |
| model     | string  | No       | Chat model override. Falls back to server default.                |
| rerank    | string  | No       | Eval-only rerank strategy override (e.g. `mmr`, `cross`, `none`). |
| hybrid    | boolean | No       | Eval-only toggle for hybrid (dense + lexical) retrieval.          |

**Response — 200 OK** (`RagResult`)
```json
{
  "answer": "Retrieval-augmented generation (RAG) is a technique that enables LLMs to retrieve and incorporate new information from external data sources before responding...",
  "sources": [
    {
      "id": 1,
      "namespace": "default",
      "title": "Retrieval-augmented generation",
      "summary": "Retrieval-augmented generation (RAG) is a technique...",
      "url": "https://en.wikipedia.org/wiki/Retrieval-augmented_generation",
      "ingestedAt": "2026-06-05T00:48:40.411367"
    }
  ],
  "logId": 12
}
```

| Field   | Type            | Notes                                                            |
|---------|-----------------|------------------------------------------------------------------|
| answer  | string          | Model answer.                                                    |
| sources | Article[]       | Retrieved articles (full `Article` objects; `embedding` hidden). |
| logId   | long            | `QueryLog.id` of the persisted audit row (use with feedback).    |

**Behavior**
- `sources`는 retrieval 순서대로 반환되며, similarity 점수는 응답에 포함되지 않는다.
- 매 호출은 `query_log` 테이블에 audit log로 기록되고, 그 PK가 `logId`로 반환된다.
- ingest된 article이 없으면 `sources: []` + LLM-only 답변.

---

### `GET /api/rag/models`

선택 가능한 chat model 목록과 기본 모델.

**Request**
```bash
curl http://localhost:8080/api/rag/models
```

**Response — 200 OK**
```json
{
  "default": "ibm/granite4:latest",
  "available": ["ibm/granite4:latest", "gemma4"]
}
```

---

## 2. Data

Controller: `DataController` — `@RequestMapping("/api/data")`.
Wikipedia REST API / 업로드 파일에서 콘텐츠를 가져와 embedding을 생성하고 영구 저장합니다.

### `POST /api/data/ingest`

Single article ingestion. Query parameter 방식.

**Request**
```bash
curl -X POST "http://localhost:8080/api/data/ingest?title=Retrieval-augmented%20generation&namespace=acme"
```

| Param     | In    | Type   | Required | Description                                    |
|-----------|-------|--------|----------|------------------------------------------------|
| title     | query | string | Yes      | Wikipedia article title (URL-encode).          |
| namespace | query | string | No       | Tenant/collection. Defaults to `"default"`.    |

**Response — 200 OK** (`Article`)
```json
{
  "id": 1,
  "namespace": "default",
  "title": "Retrieval-augmented generation",
  "summary": "Retrieval-augmented generation (RAG) is a technique...",
  "url": "https://en.wikipedia.org/wiki/Retrieval-augmented_generation",
  "ingestedAt": "2026-06-05T00:48:40.411367"
}
```

> `embedding` 필드는 `@JsonProperty(WRITE_ONLY)`로 응답에서 숨겨집니다.

---

### `POST /api/data/ingest-batch`

여러 article을 한 번에 ingest. 일부 실패해도 나머지는 계속 진행. namespace는 query parameter.

**Request**
```bash
curl -X POST "http://localhost:8080/api/data/ingest-batch?namespace=acme" \
  -H "Content-Type: application/json" \
  -d '{"topics": ["Retrieval-augmented generation", "Vector database", "Embedding"]}'
```

| Param     | In    | Type   | Required | Description                                 |
|-----------|-------|--------|----------|---------------------------------------------|
| namespace | query | string | No       | Tenant/collection. Defaults to `"default"`. |

**Request Body**
```json
{
  "topics": ["string", "..."]
}
```

**Response — 200 OK**
```json
{
  "success": true,
  "namespace": "acme",
  "ingested": 2,
  "failed": 1,
  "articles": [
    { "id": 1, "namespace": "acme", "title": "...", "summary": "...", "url": "...", "ingestedAt": "..." }
  ],
  "errors": [
    { "title": "Nonexistent Topic", "error": "404 Not Found" }
  ]
}
```

**Response — empty topics**
```json
{
  "success": false,
  "error": "Request body must contain non-empty 'topics' array"
}
```

---

### `POST /api/data/ingest-file`

텍스트 파일을 업로드하여 청크 단위로 ingest (multipart).

**Request**
```bash
curl -X POST http://localhost:8080/api/data/ingest-file \
  -F "file=@notes.txt" \
  -F "namespace=acme"
```

| Param     | In        | Type   | Required | Description                                 |
|-----------|-----------|--------|----------|---------------------------------------------|
| file      | multipart | file   | Yes      | Text file to ingest.                        |
| namespace | multipart | string | No       | Tenant/collection. Defaults to `"default"`. |

**Response — 200 OK** — 생성된 chunk article 배열 (`Article[]`).

---

### `POST /api/data/summarize/{id}`

지정 article이 속한 문서의 모든 chunk를 합쳐 chat model로 요약.

**Request**
```bash
curl -X POST http://localhost:8080/api/data/summarize/3
```

**Response — 200 OK**
```json
{
  "id": 3,
  "title": "notes.txt",
  "summary": "..."
}
```

---

### `GET /api/data/articles`

전체 article 목록 조회. namespace 미지정 시 전체 반환.

**Request**
```bash
curl "http://localhost:8080/api/data/articles?namespace=acme"
```

| Param     | In    | Type   | Required | Description                              |
|-----------|-------|--------|----------|------------------------------------------|
| namespace | query | string | No       | Filter by tenant. Omit for all articles. |

**Response — 200 OK** — `Article[]`.

---

### `GET /api/data/documents`

Chunk들을 base title 기준으로 그룹핑한 문서 단위 목록.

**Request**
```bash
curl "http://localhost:8080/api/data/documents?namespace=acme"
```

**Response — 200 OK**
```json
[
  {
    "title": "notes.txt",
    "chunks": 4,
    "namespace": "acme",
    "url": "",
    "ids": [3, 4, 5, 6]
  }
]
```

---

### `GET /api/data/count`

빠른 카운트 (dashboard용). namespace 옵션.

**Request**
```bash
curl "http://localhost:8080/api/data/count?namespace=acme"
```

**Response — 200 OK**
```json
{ "count": 3 }
```

---

### `GET /api/data/index/stats`

벡터 인덱스 상태 (mode, hyperplanes, per-namespace vectors/buckets).

**Request**
```bash
curl http://localhost:8080/api/data/index/stats
```

**Response — 200 OK** — `VectorStore.stats()` 맵 (구현에 따라 가변).

---

### `DELETE /api/data/documents`

base title + namespace로 매칭되는 모든 chunk 삭제 후 인덱스 재구성.

**Request**
```bash
curl -X DELETE "http://localhost:8080/api/data/documents?title=notes.txt&namespace=acme"
```

| Param     | In    | Type   | Required | Description                                 |
|-----------|-------|--------|----------|---------------------------------------------|
| title     | query | string | Yes      | Base document title (chunk suffix stripped).|
| namespace | query | string | No       | Tenant. Defaults to `"default"`.            |

**Response — 200 OK**
```json
{ "title": "notes.txt", "namespace": "acme", "deletedChunks": 4 }
```

---

### `DELETE /api/data/articles/{id}`

단일 article(chunk) 삭제. 삭제되면 인덱스 재구성.

**Request**
```bash
curl -X DELETE http://localhost:8080/api/data/articles/5
```

**Response — 200 OK**
```json
{ "deleted": true, "id": 5 }
```

---

## 3. Governance

Controller: `GovernanceController` — `@RequestMapping("/api/governance")`.
모든 RAG 호출은 `query_log` 테이블에 audit log로 기록됩니다 (dev/demo: H2, prod: PostgreSQL).

### `GET /api/governance/logs`

전체 query log 반환.

**Request**
```bash
curl http://localhost:8080/api/governance/logs
```

**Response — 200 OK** — `QueryLog[]`
```json
[
  {
    "id": 12,
    "question": "What is RAG?",
    "answer": "Retrieval-augmented generation (RAG) is...",
    "model": "ibm/granite4:latest",
    "latencyMs": 1842,
    "createdAt": "2026-06-05T00:50:11.231",
    "sources": "[1, 2]",
    "feedback": null,
    "piiCount": 0,
    "augmentedPrompt": "Context:\n..."
  }
]
```

---

### `GET /api/governance/stats`

대시보드용 집계 (총계 + 모델별/소스타입별/피드백별).

**Request**
```bash
curl http://localhost:8080/api/governance/stats
```

**Response — 200 OK**
```json
{
  "totalCalls": 12,
  "avgLatencyMs": 1620,
  "totalPii": 3,
  "totalDocs": 5,
  "byModel": [
    { "model": "ibm/granite4:latest", "calls": 9, "avgMs": 1700 }
  ],
  "bySourceType": [
    { "sourceType": "wikipedia", "docs": 4, "chunks": 18 }
  ],
  "feedback": [
    { "feedback": "up", "count": 5 }
  ]
}
```

---

### `POST /api/governance/feedback`

특정 log에 사용자 피드백(up/down) 기록.

**Request**
```bash
curl -X POST http://localhost:8080/api/governance/feedback \
  -H "Content-Type: application/json" \
  -d '{"id": "12", "value": "up"}'
```

**Request Body**
```json
{
  "id": "12",
  "value": "up"
}
```

| Field | Type   | Required | Description                       |
|-------|--------|----------|-----------------------------------|
| id    | string | Yes      | `QueryLog.id` (from `ask` logId). |
| value | string | Yes      | `"up"` or `"down"`.               |

**Response — 200 OK**
```json
{ "id": 12, "feedback": "up" }
```

---

## 4. Multimodal

Controller: `MultimodalController` — `@RequestMapping("/api/multimodal")`.

### `POST /api/multimodal/ask`

이미지 + 질문 → OCR 텍스트 추출(grounding) + vision model → answer (multipart).

**Request**
```bash
curl -X POST http://localhost:8080/api/multimodal/ask \
  -F "image=@chart.png" \
  -F "question=What is the Q4 revenue?"
```

| Param    | In        | Type   | Required | Description                                  |
|----------|-----------|--------|----------|----------------------------------------------|
| image    | multipart | file   | Yes      | Image to analyze.                            |
| question | multipart | string | Yes      | Question about the image.                    |
| model    | multipart | string | No       | Vision model override. Falls back to default.|

**Response — 200 OK**
```json
{
  "answer": "Q4 revenue was $4.2M.",
  "model": "granite-vision"
}
```

---

### `POST /api/multimodal/ingest`

이미지를 지식베이스에 ingest. 비전 모델이 이미지를 설명 → embedding → `Article`로 저장 → 이후 텍스트 RAG로 검색 가능 (multipart).

**Request**
```bash
curl -X POST http://localhost:8080/api/multimodal/ingest \
  -F "image=@diagram.png" \
  -F "namespace=acme"
```

| Param     | In        | Type   | Required | Description                                  |
|-----------|-----------|--------|----------|----------------------------------------------|
| image     | multipart | file   | Yes      | Image to ingest.                             |
| namespace | multipart | string | No       | Tenant. Defaults to `"default"`.             |
| model     | multipart | string | No       | Vision model override. Falls back to default.|

**Response — 200 OK** (`Article`) — `summary`에 `[OCR]` 블록과 `[Vision]` 블록이 포함됩니다.

---

## 5. Tabular (text-to-SQL)

Controller: `TabularController` — `@RequestMapping("/api/tabular")`. 표(CSV/XLSX)는 RAG가 아니라 DuckDB로 SQL 질의한다 — 집계(COUNT/AVG/SUM)는 벡터 RAG가 못 하는 영역. 상세: [TABULAR-SQL.md](TABULAR-SQL.md).

### `POST /api/tabular/load`

표 파일을 DuckDB 테이블로 등록 (CSV는 `read_csv_auto`, XLSX는 POI로 변환). 정부/기업 양식은 제목·안내행이 위에 있어 `headerRow`로 진짜 헤더부터 읽는다.

**Request**
```bash
curl -X POST "http://localhost:8080/api/tabular/load?table=revenue&path=sample/quarterly-revenue-2025.csv"
curl -X POST "http://localhost:8080/api/tabular/load?table=housing&path=sample/주택목록.xlsx&headerRow=6"
```

| Param     | In    | Type   | Required | Description                                       |
|-----------|-------|--------|----------|---------------------------------------------------|
| table     | query | string | Yes      | 테이블명 (`[A-Za-z_][A-Za-z0-9_]*`).              |
| path      | query | string | Yes      | 서버측 파일 경로 (데모: `sample/`).               |
| headerRow | query | int    | No       | xlsx 헤더 행 인덱스(0부터). 기본 0.               |

**Response — 200 OK**
```json
{ "table": "revenue", "schema": "quarter VARCHAR\nrevenue_musd DOUBLE\nregion VARCHAR\nyoy_growth_pct BIGINT\n" }
```

### `POST /api/tabular/ask`

질문 → LLM이 DuckDB SQL(SELECT) 생성 → 실행 → 결과. 생성된 SQL을 응답에 함께 돌려준다(투명성). 실패 시 `error` 포함.

**Request**
```bash
curl -X POST http://localhost:8080/api/tabular/ask \
  -H 'Content-Type: application/json' \
  -d '{"table":"revenue","question":"What was the Q3 revenue?"}'
```

| Field    | Type   | Required | Description                  |
|----------|--------|----------|------------------------------|
| table    | string | Yes      | `load`로 등록한 테이블 이름. |
| question | string | Yes      | 자연어 질문.                 |

**Response — 200 OK**
```json
{ "sql": "SELECT revenue_musd FROM revenue WHERE quarter = 'Q3'", "columns": ["revenue_musd"], "rows": [[24.1]] }
```

보안: 실행은 `SELECT`/`WITH`만 허용하고 `DROP/DELETE/UPDATE/INSERT/...` 등 위험 키워드는 차단한다.

---

## 6. Health

### `GET /actuator/health`

Spring Boot Actuator. UP/DOWN.

```bash
curl http://localhost:8080/actuator/health
```

```json
{ "status": "UP" }
```

---

## Error Responses

표준 Spring Boot 에러 응답:

```json
{
  "timestamp": "2026-06-05T00:42:05.932Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Required parameter 'title' is not present.",
  "path": "/api/data/ingest"
}
```

| Status | Meaning                                                   |
|--------|-----------------------------------------------------------|
| 200    | Success                                                   |
| 400    | Missing/invalid parameter, malformed JSON                 |
| 404    | Article (Wikipedia 404) — surfaced inside batch `errors` |
| 500    | Ollama down, I/O error, unexpected exception              |
| 503    | Ollama timeout (>30s default)                             |

---

## Data Types

### Article

| Field       | Type              | Notes                                       |
|-------------|-------------------|---------------------------------------------|
| id          | long              | Internal identifier.                        |
| namespace   | string            | Multi-tenant key; blank treated as `default`.|
| title       | string            | Article / document title.                   |
| summary     | string            | Extract text (Wikipedia summary or chunk).  |
| url         | string            | Canonical source URL (may be empty).        |
| ingestedAt  | string (ISO-8601) | LocalDateTime, e.g. `2026-06-05T00:48:40`.  |
| embedding   | float[]           | Hidden from API responses (WRITE_ONLY).     |

### QueryLog (table `query_log`)

| Field           | Type              | Notes                                       |
|-----------------|-------------------|---------------------------------------------|
| id              | long              | DB auto-PK; returned as `logId` from `ask`. |
| question        | string (TEXT)     | Full question.                              |
| answer          | string (TEXT)     | Full answer.                                |
| model           | string            | Chat model used.                            |
| latencyMs       | long              | Wall-clock ms.                              |
| createdAt       | datetime          | Server time (set on persist).               |
| sources         | string (TEXT)     | Serialized source references.               |
| feedback        | string            | `"up"`, `"down"`, or null.                  |
| piiCount        | int               | Detected PII occurrences.                   |
| augmentedPrompt | string (@Lob)     | Full prompt sent to the model.              |

### DocumentCatalog (table `document_catalog`)

| Field      | Type              | Notes                              |
|------------|-------------------|------------------------------------|
| id         | long              | DB auto-PK.                        |
| title      | string            | Document title.                    |
| namespace  | string            | Tenant/collection.                 |
| sourceType | string            | e.g. `wikipedia`, `file`, `image`. |
| chunks     | int               | Number of chunks for the document. |
| embedModel | string            | Embedding model used.              |
| ingestedAt | datetime          | Ingestion time.                    |

---

## Notes

- **Local-only**: Wikipedia REST + Ollama localhost:11434. No external paid APIs.
- **Anti-corruption**: `@JsonIgnoreProperties(ignoreUnknown=true)` on Wikipedia DTOs.
- **Defaults**: chat-model `ibm/granite4:latest` (available `ibm/granite4:latest`, `gemma4`); rerank strategy `mmr`; chunking `recursive`; hybrid retrieval enabled; `num-predict` 256.
- **Profiles**: `dev` (H2 in-memory), `demo` (H2 file), `prod` (PostgreSQL).
