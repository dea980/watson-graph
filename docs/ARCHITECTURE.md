# MiniWatson — Architecture

A small but production-shaped reference of IBM watsonx's three-pillar architecture (**data · ai · governance**), built end-to-end on a laptop.

---

## 1. Layered View

```
┌─────────────────────────────────────────────────────────────┐
│  Presentation                                                │
│  ── Static dashboard (IBM Carbon, plain CSS)                 │
│     /static/index.html  ·  app.js  ·  styles.css             │
└────────────────────────────┬─────────────────────────────────┘
                             │ fetch
┌────────────────────────────▼─────────────────────────────────┐
│  REST API  (Spring Boot 4 · Jackson 3.x)                     │
│  ── DataController · RagController                           │
│     GovernanceController · MultimodalController             │
│     TabularController                                       │
└────────────────────────────┬─────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
┌───────▼─────────┐  ┌───────▼─────────┐  ┌──────▼──────────┐
│ Service Layer   │  │ Retrieval       │  │ Governance       │
│ IngestionService│  │ HybridRetriever │  │ QueryLogRepo     │
│ IndexingService │  │ Reranker (mmr)  │  │ DocumentCatalog  │
│ EmbeddingService│  │ OllamaService   │  │ PiiRedaction     │
└───────┬─────────┘  └───────┬─────────┘  └──────┬──────────┘
        │                    │                    │
┌───────▼──────────┐  ┌──────▼───────────┐  ┌────▼────────────┐
│ ArticleRepository│  │ VectorIndex      │  │ H2 / PostgreSQL  │
│ TieredArticleStore│ │ KeywordIndex(BM25│  │ query_log +      │
│ (JSON hot+Parquet│  │ Ollama LLM       │  │ document_catalog │
│  cold)           │  │ localhost:11434  │  │ (JPA)            │
└──────────────────┘  └──────────────────┘  └──────────────────┘
```

---

## 2. Component Map

| Component               | Responsibility                                          | Maps to watsonx     |
|-------------------------|---------------------------------------------------------|---------------------|
| `IngestionService`      | Wikipedia / file / image fetch → chunk → embed → store; `extractText`가 확장자로 추출기 분기 | watsonx.data (ETL)  |
| `HwpExtractor`          | 한글 HWP/HWPX → text (hwplib/hwpxlib, HWPX는 PrvText 폴백) | watsonx.data        |
| `Chunker` (fixed/recursive/semantic) | 문서 청킹 전략 (기본 recursive)            | watsonx.data        |
| `ArticleRepository`     | Article 영속화 인터페이스 (구현: `TieredArticleStore` = JSON hot + Parquet cold) | watsonx.data |
| `IndexingService`       | ingest/reindex 시 VectorIndex + KeywordIndex 동기화     | watsonx.data        |
| `EmbeddingService`      | text → 768-dim 벡터 (`search_query:`/`search_document:`) | watsonx.ai          |
| `HybridRetriever`       | VectorIndex + KeywordIndex(BM25) RRF 융합               | watsonx.ai (vector search) |
| `Reranker` (none/llm/mmr/cross) | 후보 재정렬 (기본 mmr)                          | watsonx.ai          |
| `OllamaService`         | Ollama 호출 (chat + 멀티모달) + audit log + PII 마스킹  | watsonx.ai runtime  |
| `RagService`           | hybrid 검색 → rerank → augmented prompt → LLM           | watsonx.ai          |
| `PiiRedactionService`   | 로그 기록 전 PII 마스킹 (`[CARD][SSN][EMAIL][PHONE]`)   | watsonx.governance  |
| `QueryLogRepository`    | 모든 LLM 호출을 H2/PostgreSQL에 기록 (JPA)              | watsonx.governance  |
| `GovernanceController`  | logs / stats / feedback API                             | watsonx.governance  |
| `MultimodalController`  | 이미지 ask + ingest (OCR + Vision)                      | watsonx.ai          |
| `TabularController`     | 표(CSV/XLSX) load + 자연어 SQL 질의 (`/api/tabular/load`·`/ask`) | watsonx.data        |
| `TabularSqlService` / `TextToSqlService` | DuckDB 임베디드 SQL 엔진 + 질문→SQL 생성/실행 (SELECT 전용 가드) | watsonx.data (text-to-SQL) |

> **모달리티 분기**: 비정형 텍스트는 RAG(`/api/rag`, 벡터 검색)로, 정형 표(CSV/XLSX)는 SQL(`/api/tabular`, DuckDB)로 처리한다. COUNT/AVG/SUM 같은 집계는 벡터 RAG가 원천적으로 못 하므로 watsonx.data 라이크하우스 패턴(파일을 옮기지 않고 컬럼 엔진으로 SQL)으로 분리했다.

---

## 3. Request Flow — RAG `POST /api/rag/ask`

```
Client
  │
  │ POST {"question": "What is RAG?", "namespace"?, "model"?, "rerank"?, "hybrid"?}
  ▼
RagController
  │
  ▼
RagService.ask(question, namespace, model, rerank, hybrid)
  │
  ├─► EmbeddingService.embed("search_query: " + question)  ──► Ollama /api/embed (nomic-embed-text)
  │                                                            → float[768]
  │
  ├─► HybridRetriever.search(ns, qVec, question, FETCH_N=20)
  │      ├─ VectorIndex.search(ns, qVec, 20)   (cosine, LSH off → brute-force)
  │      ├─ KeywordIndex.search(ns, query, 20) (BM25)
  │      └─ Reciprocal Rank Fusion (k=60)      → List<Article> (20 후보)
  │
  ├─► Reranker.rerank(question, candidates, TOP_K=2)   (기본 mmr)
  │                                                     → top-2 sources
  │
  ├─► buildPrompt(question, topSources)         ──► context + question
  │
  ├─► OllamaService.ask(prompt, model, question, sources)  ──► Ollama /api/generate (ibm/granite4)
  │      └─ PiiRedactionService.redact(...)     (질문/답변 PII 마스킹)
  │      └─ QueryLog save (question, answer, model, latencyMs, sources, piiCount, augmentedPrompt)
  │                                              ──► H2 / PostgreSQL
  │
  ▼
{ answer, sources[], logId }
```

---

## 4. Request Flow — Ingestion `POST /api/data/ingest-batch`

```
Client
  │ POST {"topics": ["RAG", "Vector database", ...]}
  ▼
DataController
  │
  └─► for each topic:
        │
        ├─► IngestionService.ingest(title)
        │     │
        │     ├─► WikipediaClient.fetchSummary(title)
        │     │      GET https://en.wikipedia.org/api/rest_v1/page/summary/{title}
        │     │      Headers: User-Agent: MiniWatson/1.0 (mailto:kdea989@gmail.com)
        │     │
        │     ├─► EmbeddingService.embed("search_document: " + text) → float[768]
        │     │
        │     ├─► ArticleRepository.save(article)        ──► TieredArticleStore (JSON hot + Parquet cold)
        │     │
        │     └─► IndexingService.index(article)
        │            ├─► VectorIndex.add(article)        (인메모리 벡터 인덱스)
        │            └─► KeywordIndex.add(article)       (BM25 역색인)
        │
        └─► aggregate to {success, namespace, ingested, failed, articles, errors}
```

> **Note**: `ingest-batch` 는 `?namespace=` 로 tenant 분리. 파일(`ingest-file`)·이미지(`/api/multimodal/ingest`) ingest는 Chunker(기본 recursive)로 청킹 후 청크별 Article + `document_catalog` 한 행. VectorIndex/KeywordIndex는 startup 시 `loadAll()`로 hydrate되고 ingest마다 증분 갱신.

---

## 5. watsonx Mapping (DAP ↔ watsonx)

| MiniWatson layer        | DAP component             | watsonx counterpart           |
|-------------------------|---------------------------|-------------------------------|
| Wikipedia / file / image ingest | Data Acquisition  | watsonx.data (ingest)         |
| Article + embedding     | Knowledge Lake            | watsonx.data (object store)   |
| Chunker (recursive 등)  | Document processing       | watsonx.data                  |
| TieredArticleStore (JSON+Parquet) | Columnar storage | watsonx.data                  |
| Ollama (granite4, nomic)| Text Platform             | watsonx.ai (foundation model) |
| Hybrid retrieval (RRF)  | Retrieval                 | watsonx.ai (vector + keyword) |
| Reranker (mmr 등)       | Reranking                 | watsonx.ai                    |
| RAG prompt build        | Inference pipeline        | watsonx.ai                    |
| QueryLog (H2/PostgreSQL)| Decision Log              | watsonx.governance            |
| PII redaction           | Data masking              | watsonx.governance            |
| `@JsonIgnoreProperties` | Anti-corruption layer     | data sovereignty pattern      |

---

## 6. Profiles

`application-{profile}.yaml` via Spring profiles.

> Storage = governance DB (`query_log`, `document_catalog`). Article 본문은 모든 프로파일에서 `TieredArticleStore` (JSON hot + Parquet cold) 파일 기반.

| Profile | Use case            | Governance DB    | LLM     | Notes                                  |
|---------|---------------------|------------------|---------|----------------------------------------|
| `dev`   | Local dev (default) | H2 in-memory     | Ollama  | `ddl-auto=create-drop`, H2 console     |
| `demo`  | Live demo / IBM     | H2 file (`./data`)| Ollama | `ddl-auto=update`, 데이터 영속         |
| `prod`  | Production          | PostgreSQL       | Ollama  | `PostgreSQLDialect`, `ddl-auto=update`; pgvector는 미연결 |

Activate via:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

---

## 7. Non-Functional Notes

- **Embedding dim**: 768 (`nomic-embed-text`, `search_query:`/`search_document:` 프리픽스).
- **Retrieval**: `FETCH_N=20` 후보 → rerank → `TOP_K=2` (둘 다 `RagService` 상수).
- **Hybrid**: VectorIndex + KeywordIndex(BM25)를 RRF(`k=60`)로 융합 (`retrieval.hybrid.enabled=true`).
- **Vector index**: LSH 기본 off → namespace별 brute-force cosine (`vector.index.lsh.enabled=false`).
- **Rerank**: 기본 `mmr` (`rerank.strategy`); `cross`는 DJL cross-encoder, Intel Mac에서 폴백.
- **Chunking**: 기본 `recursive` (`chunking.strategy`, `max-size=1000`).
- **Multi-format ingest**: `IngestionService.extractText`가 확장자로 분기 — Tika(PDF/DOCX/PPTX/XLSX/HTML), HWP/HWPX는 `HwpExtractor`(hwpxlib NPE 시 PrvText 폴백), 이미지는 OCR+Vision. 추출 이후 청킹/임베딩/인덱싱은 포맷 무관 (INGESTION-FORMATS.md).
- **LLM token budget**: `num_predict=256` with `think: false`.
- **Multi-tenant**: 모든 인덱스/검색이 `namespace`로 분리.

---

## 8. What's Intentionally Simple

These are **scope choices**, not bugs:

| Simplification              | Production version would…                            |
|-----------------------------|------------------------------------------------------|
| H2 audit log (dev/demo)     | PostgreSQL (prod already) + retention policy         |
| In-memory VectorIndex/LSH   | FAISS / pgvector / Milvus for >10k articles          |
| No auth on RAG              | Per-tenant API keys + rate limit (namespace는 이미 분리) |
| Regex PII redaction         | NER 기반 PII 탐지 / 정책 엔진                        |
| Single-node Ollama          | watsonx.ai or LLM proxy with HA                      |

These are mapped 1:1 in `docs/DATA-MODEL.md` and `docs/GOVERNANCE.md`.

---

## 9. Why This Architecture (for IBM)

1. **Three-pillar parity** — data · ai · governance, mirroring watsonx.
2. **Anti-corruption** — Wikipedia DTO은 internal Article로 격리 (`@JsonIgnoreProperties`).
3. **Auditable by default** — 모든 RAG 호출이 governance log에 남음.
4. **Sovereign by design** — 모든 컴포넌트 localhost, 외부 SaaS 의존성 0.
5. **Java 21 + Spring Boot 4** — 최신 LTS + 최신 Spring (Jackson 3.x).
6. **OpenJ9 narrative** — IBM Semeru JDK 권장 (`pom.xml` 주석에 기록).
