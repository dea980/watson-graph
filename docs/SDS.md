# MiniWatson — Software Design Specification (SDS)

> 패키지별 · 클래스별 명세. 코드를 수정하기 전에 해당 섹션을 먼저 확인하세요.

---

## 0. Package Map

| Package | 책임 | 파일 |
|---|---|---|
| `com.miniwatson` | Spring Boot 부트스트랩 | `MiniwatsonApplication.java` |
| `com.miniwatson.controller` | HTTP presentation | `RagController`, `DataController`, `GovernanceController`, `MultimodalController`, `TabularController` |
| `com.miniwatson.service` | 비즈니스 로직 (AI + ingestion + RAG + retrieval + tabular SQL) | `OllamaService`, `EmbeddingService`, `IngestionService`, `RagService`, `HybridRetriever`, `IndexingService`, `Chunker`(fixed/recursive/semantic), `Reranker`(none/llm/mmr/cross), `OcrService`, `TabularSqlService`, `TextToSqlService` |
| `com.miniwatson.data` | 도메인 + 영속화 + 인덱스 | `Article`, `WikipediaResponse`, `ArticleRepository`(인터페이스) / `TieredArticleStore`(@Primary), `ArticleStore`(JSON hot), `ArticleParquetStore`(Parquet cold), `VectorStore`/`VectorIndex`, `KeywordIndex` |
| `com.miniwatson.governance` | audit + 카탈로그 + PII | `QueryLog`, `QueryLogRepository`, `DocumentCatalog`, `DocumentCatalogRepository`, `PiiRedactionService` |
| `com.miniwatson.dto` | 외부 API 요청/응답 모델 | `AskRequest`, `OllamaRequest`, `OllamaResponse`, `EmbeddingRequest`, `EmbeddingResponse` |

---

## 1. Controllers

> 컨트롤러는 5개: `RagController` · `DataController` · `GovernanceController` · `MultimodalController` · `TabularController`. 별도 Hello/Ask 컨트롤러나 `/api/hello`·`/api/version`·`/api/ask` 엔드포인트는 없다.

### 1.1 RagController
- **URL**: `POST /api/rag/ask`, `GET /api/rag/models`
- **request body** (`/ask`): `AskRequest { question, namespace?, model?, rerank?, hybrid? }`
  - `namespace` (없으면 `"default"`), `model` (없으면 서버 기본 모델)
  - `rerank` · `hybrid` 은 **EVAL-ONLY** 오버라이드 (`eval.overrides.enabled` 켜진 dev/demo에서만 적용)
- **response** (`/ask`): `RagService.RagResult { answer, sources[], logId }`
- **흐름**: `ragService.ask(question, namespace, model, rerank, hybrid)` 위임.
- **`GET /models`**: `{ "default": ..., "available": [...] }` — 멀티 LLM 선택용 화이트리스트.
- **에러**: 해당 namespace에 article 없으면 `RuntimeException("No articles in knowledge base for namespace '...'.")` → 500.

### 1.2 MultimodalController
- **URL**: `POST /api/multimodal/ask`, `POST /api/multimodal/ingest` (둘 다 multipart)
- **`/ask`**: params `image`, `question`, `model?` → OCR로 텍스트 grounding + 비전 모델 호출 → `{ answer, model }`.
- **`/ingest`**: params `image`, `namespace?`, `model?` → 비전 캡션 + OCR을 합쳐 `[OCR]…[Vision]…` 요약으로 Article 저장 (이후 텍스트 RAG로 검색 가능).
- **흐름**: `OcrService.extract()` + `OllamaService.askWithImages()` / `IngestionService.ingestImage()`.

### 1.3 DataController
| Endpoint | Method | Body / Param | 동작 |
|---|---|---|---|
| `/api/data/ingest` | POST | `?title=` `&namespace=`(opt) | 단건 ingest |
| `/api/data/ingest-batch` | POST | `{ "topics": [...] }` `?namespace=`(opt) | 다건 ingest, 실패도 함께 반환 |
| `/api/data/ingest-file` | POST | multipart `file`, `namespace?` | 임의 파일(PDF/docx/txt…) 추출 → 청킹 → 청크별 Article |
| `/api/data/summarize/{id}` | POST | path `id` | 같은 문서의 청크를 모아 LLM 요약 |
| `/api/data/articles` | GET | `?namespace=`(opt) | Article 목록 (namespace 필터) |
| `/api/data/documents` | GET | `?namespace=`(opt) | base 제목 + namespace로 그룹핑한 문서 목록 |
| `/api/data/count` | GET | `?namespace=`(opt) | `{ "count": N }` (대시보드용) |
| `/api/data/index/stats` | GET | - | `VectorStore.stats()` (mode/hyperplanes/per-ns) |
| `/api/data/documents` | DELETE | `?title=` `&namespace=`(opt) | 문서(청크 전체) 삭제 + reindex |
| `/api/data/articles/{id}` | DELETE | path `id` | 단건 Article 삭제 + reindex |

- **batch ingest의 응답 스키마**:
  ```json
  { "success": true, "namespace": "default", "ingested": N, "failed": M,
    "articles": [...], "errors": [{"title":"X","error":"..."}, ...] }
  ```
- **embedding 필드**: `@JsonProperty(WRITE_ONLY)` 로 응답에서 자동 제거.
- **삭제 후 동기화**: `deleteById` 후 `indexingService.reindex(loadAll())` 로 VectorIndex/KeywordIndex 재구성.

### 1.4 GovernanceController
- **URL**: `GET /api/governance/logs`, `GET /api/governance/stats`, `POST /api/governance/feedback`
- **`/logs`**: `List<QueryLog>` (JSON, 정렬 없음 — `findAll()` 기본 순서).
- **`/stats`**: 카드/차트용 집계 — `totalCalls`, `avgLatencyMs`, `totalPii`, `totalDocs`, `byModel[]`, `bySourceType[]`, `feedback[]`.
- **`/feedback`**: body `{ id, value }` → 해당 `QueryLog.feedback` 업데이트 ("up"/"down").

### 1.5 TabularController
- **URL**: `POST /api/tabular/load`, `POST /api/tabular/ask`
- **`/load`**: params `table`, `path`, `headerRow?`(기본 0) → `.xlsx` 면 `registerXlsx`(POI, headerRow부터), 그 외는 `registerCsv`(DuckDB `read_csv_auto`) → `{ table, schema }`.
- **`/ask`**: body `{ table, question }` → `TextToSqlService.ask()` (질문+스키마+샘플 → LLM이 DuckDB SQL 생성 → 실행) → `{ sql, columns, rows }`(에러 시 `{ sql, error, rows:[] }`).
- **경로 분리**: 비정형 텍스트는 `/api/rag`(벡터 RAG), 정형 표(CSV/XLSX)는 `/api/tabular`(DuckDB text-to-SQL). COUNT/AVG/SUM/필터 집계는 벡터 RAG가 못 해 SQL로 분리 — watsonx.data 라이크하우스 패턴(파일을 옮기지 않고 컬럼 엔진으로 SQL).

---

## 2. Services

### 2.1 OllamaService
**역할**: chat / 멀티모달 모델 호출 + **governance audit log 기록** + **PII 마스킹**.

```java
@Service
public class OllamaService {
    @Value("${ollama.url}")          String ollamaUrl;
    @Value("${ollama.chat-model}")   String defaultModel;
    @Value("${ollama.chat-models:}") String chatModelsCsv;   // 멀티 LLM 화이트리스트
    @Value("${ollama.num-predict}")  int    numPredict;
    private final RestTemplate restTemplate = new RestTemplate();
    private final QueryLogRepository  queryLogRepository;     // ← governance hook
    private final PiiRedactionService piiRedactionService;    // ← PII 마스킹
    public String ask(String prompt, String model, String userQuestion, String sources) { ... }
    public String askWithImages(String prompt, String visionModel, List<String> base64Images) { ... }
}
```

**핵심 `generate(...)` sequence** (모든 ask 오버로드가 위임):
1. `startTime = currentTimeMillis()`
2. `OllamaRequest` 조립:
   - `model` = 호출자가 고른 모델 (화이트리스트 검증) 또는 기본 모델
   - `stream = false`, **`think = false`**, `keepAlive = "10m"`
   - `options = { "num_predict": ${ollama.num-predict} }`  (현재 256)
   - 이미지가 있으면 `images = [base64...]` (멀티모달)
3. `restTemplate.postForObject(ollamaUrl + "/api/generate", request, OllamaResponse.class)`
4. `latency = now - startTime`
5. **PII 마스킹**: `piiRedactionService.redact(userQuestion)` + `redact(answer)` → 마스킹된 텍스트 + count.
6. `QueryLog{ question(masked), answer(masked), model, latencyMs, sources, piiCount, augmentedPrompt }` save → H2/PostgreSQL. 저장된 id는 `lastQueryLogId` 에 보관 (`RagService` 가 `RagResult.logId` 로 반환).
7. return `response.getResponse()` (null이면 `"Error: no response"`).

**모델 선택**: `availableModels()` = 기본 모델 + `ollama.chat-models` CSV. `resolveModel()` 이 화이트리스트 밖 모델 요청을 거부. 비전 모델(`askWithImages`)은 화이트리스트 밖이라 그대로 사용.

**의도된 design choice**:
- audit log는 서비스 안에 있어야 한다 (controller 안이 아님) → ingest, direct ask, RAG 어디서 부르든 누락 안 됨.
- timestamp는 `QueryLog.@PrePersist` 에서 채워짐.

**알려진 한계**:
- `RestTemplate` 에 timeout 미설정 → Ollama 멈추면 무한 대기 가능.
- governance 저장 실패 시 답변도 함께 실패 (트랜잭션 분리 X) — 학습 프로젝트에서는 의도된 단순함.

### 2.2 EmbeddingService
**역할**: text → 768-dim float vector.

```java
public List<Float> embed(String text) {
    EmbeddingRequest req = new EmbeddingRequest();
    req.setModel(embedModel);          // nomic-embed-text
    req.setInput(text);
    EmbeddingResponse resp = restTemplate.postForObject(
        ollamaUrl + "/api/embed", req, EmbeddingResponse.class);
    return resp.getEmbeddings().get(0); // 2D → 1D
}
```

**왜 `List<List<Float>>` 인가?**: Ollama `/api/embed` 는 batch input을 받을 수 있어 응답이 2D. 우리는 항상 single input → 첫 번째만 사용.

**실패 처리**: null/empty면 `RuntimeException("No embedding returned for text: …")`.

### 2.3 IngestionService
**역할**: Wikipedia → 정규화된 `Article` → embedding → store.

핵심 코드:
```java
String url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + title;
HttpHeaders headers = new HttpHeaders();
headers.set("User-Agent",
   "MiniWatson/1.0 (https://github.com/dea980/miniwatson; kdea989@gmail.com)");
// 주의: User-Agent 없이 호출하면 Wikipedia가 403 반환
ResponseEntity<WikipediaResponse> r = restTemplate.exchange(url, GET, request, WikipediaResponse.class);

Article a = new Article();
a.setNamespace(ns);                                  // tenant 분리 (없으면 "default")
a.setTitle(r.getBody().getTitle());
a.setSummary(r.getBody().getExtract());
a.setUrl(r.getBody().getContent_urls().getDesktop().getPage());
a.setIngestedAt(LocalDateTime.now());
a.setEmbedding(embeddingService.embed("search_document: " + a.getTitle() + ". " + a.getSummary()));
Article saved = articleStore.save(a);                // ArticleRepository (JPA)
indexingService.index(saved);                        // VectorIndex + KeywordIndex 갱신
return saved;
```

**진입점**: `ingest(title)`(default ns) · `ingest(title, namespace)` · `ingestText(file, ns)`(파일 청킹) · `ingestImage(image, ns, model)`(멀티모달). 단건 ingest는 namespace 내 제목 중복 시 기존 Article 반환(dedupe).

**embedding 입력 텍스트**: `"search_document: {title}. {summary}"`. 질의는 `"search_query: …"` 프리픽스 (nomic 비대칭 임베딩). 프리픽스/포맷 변경 시 기존 articles 전체 재임베딩 필요.

### 2.4 RagService
**역할**: hybrid retrieval + rerank + augmentation + LLM 호출.

```java
private static final int TOP_K   = 2;    // LLM 에 최종 전달
private static final int FETCH_N = 20;   // rerank 후보군 (1차 검색)

public RagResult ask(String question, String namespace, String model,
                     String rerankOverride, Boolean hybridOverride) throws IOException {
    String ns = (namespace == null || namespace.isBlank()) ? "default" : namespace;

    // EVAL-ONLY 게이트: eval.overrides.enabled 일 때만 요청별 rerank/hybrid 오버라이드 허용
    Reranker rr = reranker;            // 기본은 rerank.strategy (mmr)
    Boolean  hy = null;
    if (evalOverrides) { if (rerankOverride != null && rerankers.containsKey(rerankOverride)) rr = rerankers.get(rerankOverride); hy = hybridOverride; }

    List<Float> qv = embeddingService.embed("search_query: " + question);
    List<Article> candidates = hybridRetriever.search(ns, qv, question, FETCH_N, hy);
    if (candidates.isEmpty()) throw new RuntimeException("No articles in knowledge base for namespace '" + ns + "'.");

    List<Article> top = rr.rerank(question, candidates, TOP_K);   // 재정렬 → 최종 2건

    StringBuilder ctx = new StringBuilder();
    for (Article a : top) {                                       // 소스당 최대 600자
        String s = a.getSummary(); if (s.length() > 600) s = s.substring(0, 600);
        ctx.append("- ").append(a.getTitle()).append(": ").append(s).append("\n");
    }
    String sources = top.stream().map(a -> "#" + a.getId() + " " + a.getTitle()).collect(joining("; "));

    String prompt = "Use the context below. For exact numbers, trust [OCR] sections over [Vision] descriptions.\n"
                  + ctx + "\nAnswer concisely: " + question;

    String answer = ollamaService.ask(prompt, model, question, sources);  // governance log + PII 자동
    return new RagResult(answer, top, ollamaService.lastQueryLogId());
}
```

**파이프라인**: `embed(search_query:)` → `HybridRetriever.search` (VectorIndex + KeywordIndex(BM25)를 RRF로 융합, FETCH_N=20) → `Reranker.rerank` (기본 mmr, TOP_K=2) → augmented prompt → `OllamaService.ask`.

**튜닝 포인트**:
- `TOP_K=2` / `FETCH_N=20`: 후보를 넓게 뽑아 rerank로 좁힘. TOP_K가 크면 prompt 길이 폭증.
- prompt template: `[OCR]`/`[Vision]` 멀티모달 소스를 고려한 지시문. 모델 교체 시 재실험.
- 1차 검색은 `VectorIndex` (LSH off → namespace별 brute-force cosine) + BM25. N이 커지면 LSH on 또는 외부 ANN 검토.

**`RagResult`**: Java `record(String answer, List<Article> sources, Long logId)`. `sources[*].embedding` 은 `@JsonProperty(WRITE_ONLY)` 로 숨김. `logId` 로 `/api/governance/feedback` 연동.

### 2.5 HybridRetriever
**역할**: 벡터(의미) + 키워드(BM25) 후보를 Reciprocal Rank Fusion(RRF, `k=60`)으로 융합.
```java
public List<Article> search(String ns, List<Float> qVec, String queryText, int topN, Boolean hybridOverride) {
    boolean useHybrid = (hybridOverride != null) ? hybridOverride : hybridEnabled;
    List<Article> vec = vectorIndex.search(ns, qVec, topN);
    if (!hybridEnabled) return vec;                       // 벡터-only
    List<Article> kw  = keywordIndex.search(ns, queryText, topN);
    // 각 리스트의 rank로 1/(RRF_K + rank + 1) 점수 누적 → 합산 정렬 → topN
}
```
- `retrieval.hybrid.enabled` (기본 true)로 hybrid on/off. `hybridOverride`는 EVAL-ONLY 경로에서만.

### 2.6 Reranker (전략 빈)
- 인터페이스: `List<Article> rerank(String question, List<Article> candidates, int topK)`.
- 빈 이름 = 설정 키: `none`(NoopReranker) · `llm`(LlmReranker) · `mmr`(MmrReranker) · `cross`(CrossEncoderReranker).
- `rerank.strategy` (기본 **mmr**)로 선택. `RagService` 가 `Map<String,Reranker>` 주입받아 키로 고름 (없으면 `llm` 폴백).
- `mmr`: 관련도 vs 다양성 (LAMBDA=0.6). `cross`: DJL `BAAI/bge-reranker-base` cross-encoder, 모델 로드 실패(예: Intel Mac) 시 상위 topK로 폴백.

### 2.7 Chunker (전략 빈)
- 인터페이스: `List<String> chunk(String text, int maxSize)`.
- 빈 이름: `fixed`(FixedChunker) · `recursive`(RecursiveChunker) · `semantic`(SemanticChunker).
- `chunking.strategy` (기본 **recursive**), `chunking.max-size` (기본 1000)으로 선택. `IngestionService.ingestText()` 에서 사용.

### 2.8 IndexingService
**역할**: ingest/삭제 시 모든 검색 인덱스를 한 곳에서 갱신 (ingest 로직과 분리).
- `index(Article)` → `vectorIndex.add` + `keywordIndex.add` (증분).
- `reindex(List<Article>)` → 둘 다 `rebuild` (삭제 후 동기화).

### 2.9 OcrService
**역할**: 이미지 bytes → 임시 PNG → `tesseract <file> stdout` 실행 → 추출 텍스트. 멀티모달 ingest/ask에서 정확한 숫자/텍스트 grounding 용. 실패 시 빈 문자열 반환.

### 2.10 TabularSqlService
**역할**: 표 파일을 DuckDB(임베디드, 인메모리 `jdbc:duckdb:`)로 그 자리에서 질의 — watsonx.data 라이크하우스 패턴(파일을 옮기지 않고 컬럼 엔진으로 SQL).
- `registerCsv(table, path)`: `CREATE OR REPLACE TABLE ... AS SELECT * FROM read_csv_auto(path, normalize_names=true)` (zero-ETL, 공백 컬럼명 정규화).
- `registerXlsx(table, path, headerRow)`: Apache POI로 `headerRow`부터 읽어 임시 CSV로 변환 후 `registerCsv` 합류 (정부/기업 양식의 상단 안내행 스킵). DuckDB excel 확장(네트워크) 대신 오프라인 POI.
- `schema(table)` / `sample(table, n)`: `DESCRIBE` + 샘플 행 → LLM 컨텍스트.
- `runSelect(sql)`: `requireReadOnly` 가드(SELECT/WITH로 시작 + DROP/DELETE/UPDATE/INSERT 등 금지 키워드 차단)를 통과한 쿼리만 실행, 최대 100행. `safeIdent` 로 테이블명 검증.

### 2.11 TextToSqlService
**역할**: 질문 + 스키마 + 샘플 행 → LLM이 DuckDB SQL(SELECT)을 생성 → `runSelect` 실행. 벡터 RAG가 못 하는 집계/필터/카운트를 SQL로 정확히 답한다.
- `ask(table, question)`: 프롬프트(스키마/샘플/공백 컬럼 인용 지시) → `OllamaService.ask`(기본 granite4, 감사 로그 자동) → `cleanSql`(코드펜스/설명/세미콜론 제거) → 실행. 잘못된 SQL은 500 대신 `{ sql, error, rows:[] }` 로 진단 가능하게 반환.

---

## 3. Sequence Diagrams

### 3.1 Ingest
```
Client       DataController     IngestionService    EmbeddingService    ArticleRepo + IndexingService   Wikipedia   Ollama
  │ POST ingest?title=X │              │                  │                    │              │           │
  │─────────────────────►│             │                  │                    │              │           │
  │                     │  ingest(X,ns)│                  │                    │              │           │
  │                     │─────────────►│                  │                    │              │           │
  │                     │              │ GET summary/X    │                    │              │           │
  │                     │              │  (User-Agent)    │                    │              │           │
  │                     │              │──────────────────┼────────────────────┼─────────────►│           │
  │                     │              │◄─────────────────┼────────────────────┼──────────────│           │
  │                     │              │  WikipediaResp.  │                    │              │           │
  │                     │              │  embed("search_document: "+text)      │              │           │
  │                     │              │─────────────────►│                    │              │           │
  │                     │              │                  │  POST /api/embed   │              │           │
  │                     │              │                  │────────────────────┼──────────────┼──────────►│
  │                     │              │                  │◄───────────────────┼──────────────┼────────── │
  │                     │              │◄─ List<Float>(768)                    │              │           │
  │                     │              │  save(article)   │                    │              │           │
  │                     │              │─────────────────────────────────────► │              │           │
  │                     │              │  index(article) → VectorIndex.add + KeywordIndex.add │           │
  │                     │              │◄─ Article(id set)│                    │              │           │
  │                     │◄─ Article    │                  │                    │              │           │
  │◄─ Article JSON ─────│              │                  │                    │              │           │
```

### 3.2 RAG Q&A
```
Client      RagController   RagService   EmbeddingService   HybridRetriever+Reranker   OllamaService   H2/PG (gov)   Ollama
  │ POST /rag/ask │             │             │                    │                   │                │            │
  │──────────────►│             │             │                    │                   │                │            │
  │               │ ask(q,ns,…) │             │                    │                   │                │            │
  │               │────────────►│             │                    │                   │                │            │
  │               │             │ embed("search_query: "+q)        │                   │                │            │
  │               │             │────────────►│ POST /api/embed    │                   │                │            │
  │               │             │             │────────────────────┼───────────────────┼────────────────┼───────────►│
  │               │             │             │◄ List<Float>(768) ─┼───────────────────┼────────────────┼────────────│
  │               │             │ search(ns, qVec, q, FETCH_N=20)  │                   │                │            │
  │               │             │────────────────────────────────► │ VectorIndex + KeywordIndex → RRF   │            │
  │               │             │◄─ 20 candidates ─────────────────│                   │                │            │
  │               │             │ rerank(q, candidates, TOP_K=2)   │ (기본 mmr)        │                │            │
  │               │             │◄─ top-2 sources ─────────────────│                   │                │            │
  │               │             │ build augmented prompt           │                   │                │            │
  │               │             │ ask(prompt, model, q, sources)   │                   │                │            │
  │               │             │─────────────────────────────────────────────────────►│                │            │
  │               │             │                                  │  PII redact(q, answer)              │            │
  │               │             │                                  │                   │ POST /api/generate          │
  │               │             │                                  │                   │────────────────┼───────────►│
  │               │             │                                  │                   │◄─ answer ──────┼────────────│
  │               │             │                                  │                   │ save QueryLog  │            │
  │               │             │                                  │                   │  (+sources,piiCount,prompt) │
  │               │             │                                  │                   │───────────────►│            │
  │               │             │◄ answer + lastQueryLogId ─────────────────────────────│                │            │
  │               │◄─ RagResult{answer, sources, logId}                                │                │            │
  │◄─ JSON ───────│                                                                                                  │
```

---

## 4. Data Layer — Detailed

### 4.1 `Article` (POJO)
```java
@Data
public class Article {
    private long          id;
    private String        title;
    private String        summary;
    private String        url;
    private LocalDateTime ingestedAt;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private List<Float>   embedding;   // 768 dims
}
```

- `@Data` (Lombok): getter/setter/equals/hashCode/toString 자동.
- **`embedding` 의 `WRITE_ONLY`**: Jackson 직렬화 시 **응답에서 제외**, 역직렬화 시 **요청에서 허용**. 클라이언트가 embedding 채워서 ingest하는 미래 시나리오 대비 + 응답 50KB+ 절약.

### 4.2 `WikipediaResponse` (외부 DTO, anti-corruption)
```java
@Data @JsonIgnoreProperties(ignoreUnknown = true)
public class WikipediaResponse {
    private String title;
    private String extract;
    private ContentUrls content_urls;          // snake_case 그대로

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentUrls { private Desktop desktop; }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Desktop     { private String page; }
}
```
- **inner class는 반드시 `static`** — non-static이면 Jackson이 outer instance 없이 만들 수 없어 deserialize 실패.
- 새 필드 (description, thumbnail, …) 가 필요해지면 그대로 필드 추가하면 됨 (`ignoreUnknown=true`).

### 4.3 `ArticleRepository` + `TieredArticleStore` (active)
- `ArticleRepository` (인터페이스): `loadAll`, `save`, `saveAll`, `deleteById`. 컨트롤러/서비스는 이 타입만 주입받는다.
- `TieredArticleStore` (`@Component @Primary`, **실제 주입되는 구현**): `ArticleStore`(JSON, hot) + `ArticleParquetStore`(Parquet, cold) 2계층. `save` 는 hot(JSON)에 append하고, hot 크기가 `storage.tier.threshold` (기본 100) 이상이면 cold(Parquet)로 compact. `loadAll` 은 cold + hot 병합. id는 전체 max+1.
- 아래 4.3.1/4.4는 그 두 계층의 구현 디테일.

### 4.3.1 `ArticleParquetStore` (cold tier)
**경로**: `./data/articles.parquet` (working directory 기준).

**Schema** (`src/main/resources/article.avsc`):
```json
{
  "type": "record",
  "name": "Article",
  "namespace": "miniwatson.schema",
  "fields": [
    {"name":"id",         "type":"long"},
    {"name":"title",      "type":"string"},
    {"name":"summary",    "type":"string"},
    {"name":"url",        "type":"string"},
    {"name":"ingestedAt", "type":"string"},
    {"name":"embedding",  "type":{"type":"array","items":"float"}}
  ]
}
```

**메서드**:

| 메서드 | 동작 | 시간복잡도 |
|---|---|---|
| `saveAll(List<Article>)` | 기존 파일 삭제 → SNAPPY로 재작성 | O(N·d) |
| `loadAll()` | sequential read → List<Article> | O(N·d) |
| `save(Article)` | `loadAll → set id (size+1) → append → saveAll` | O(N·d) |

**주의**:
- Parquet은 **in-place mutation 불가**. `save()` 1건도 전체 재작성. 데이터 수십~수백 건까지는 OK.
- `LocalDateTime` 은 Avro에 native가 없어 `String` (`.toString()` ISO-8601) 으로 보관. `loadAll()` 에서 `LocalDateTime.parse()` 로 역변환.
- Reader는 `withDataModel()` **호출 금지** (Parquet 1.14 API에서 NPE 유발 — 이미 빠져 있음).

### 4.4 `ArticleStore` (hot tier, JSON)
JSON append 기반 저장소. **`TieredArticleStore` 의 hot 계층으로 주입되어 현재도 사용 중** (과거 standalone 시절의 "legacy" 표기는 더 이상 맞지 않음). 새로 ingest된 Article이 먼저 여기에 쌓이고, `storage.tier.threshold` 초과 시 Parquet cold tier로 compact된다.

---

## 5. Governance Layer

### 5.1 `QueryLog` (JPA Entity)
```java
@Entity @Data @Table(name = "query_log")
public class QueryLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(columnDefinition = "TEXT") private String question;   // PII 마스킹 후
    @Column(columnDefinition = "TEXT") private String answer;     // PII 마스킹 후
    private String        model;
    private Long          latencyMs;
    private LocalDateTime createdAt;
    @Column(columnDefinition = "TEXT") private String sources;    // "#id title; …"
    private String        feedback;   // "up" | "down" | null
    private int           piiCount;   // 질문+답변에서 마스킹된 PII 개수
    @Lob   private String augmentedPrompt;                        // LLM에 보낸 전체 프롬프트

    @PrePersist void onCreate() { this.createdAt = LocalDateTime.now(); }
}
```

- `IDENTITY` 전략: H2/PostgreSQL 모두 호환.
- `TEXT`/`@Lob`: 긴 prompt/answer/sources 보관.
- `@PrePersist`: createdAt을 entity가 직접 채움 → JPA flush 시점.
- `sources`, `piiCount`, `augmentedPrompt` 는 `OllamaService.generate()` 가 채움. `feedback` 은 `/api/governance/feedback` 으로 사후 업데이트.

### 5.2 `QueryLogRepository`
```java
public interface QueryLogRepository extends JpaRepository<QueryLog, Long> { }
```
기본 메서드만 사용 (`save`, `findAll`). 확장 시 `findByModelOrderByCreatedAtDesc(...)` 같은 derived query를 추가하면 됨.

### 5.3 Audit hook 위치
**`OllamaService.generate()` 내부에만 존재** (모든 `ask`/`askWithImages` 가 위임). 즉:
- `/api/rag/ask`, `/api/multimodal/ask`, `/api/data/summarize/{id}` → audit O (LLM 호출 경유)
- `/api/data/ingest` → audit X (embedding-only, 의도된 분리)
- 기록 직전 `PiiRedactionService` 로 질문/답변 마스킹 → `piiCount` 기록.
- 향후 다른 LLM 서비스를 만들면 같은 패턴으로 hook 박아야 함. AOP 분리는 미적용.

### 5.4 `DocumentCatalog` + `PiiRedactionService`
- `DocumentCatalog` (`document_catalog` 테이블): `{ id, title, namespace, sourceType, chunks, embedModel, ingestedAt }`. 파일/이미지 ingest 시 문서 단위 한 행 (청크 수 집계). startup 시 기존 Article로 hydrate.
- `PiiRedactionService`: 정규식 기반 마스킹. 라벨 `[CARD] [SSN] [EMAIL] [PHONE]` (카드/SSN을 PHONE보다 먼저 매칭해 겹침 방지). `Redaction(text, count)` 반환.

---

## 6. DTOs

| DTO | 사용처 | 필드 | 비고 |
|---|---|---|---|
| `AskRequest` | inbound (`/api/rag/ask`) | `question`, `namespace?`, `model?`, `rerank?`, `hybrid?` | `rerank`/`hybrid` 은 EVAL-ONLY |
| `OllamaRequest` | outbound → Ollama | `model`, `prompt`, `stream`, `options`, `think`, `images?`, `keepAlive` | `think=false` 고정; `images` 는 멀티모달 |
| `OllamaResponse` | inbound ← Ollama | `model`, `createdAt`, `response`, `done` | 나머지 필드는 주석으로 기록 (필요 시 활성화) |
| `EmbeddingRequest` | outbound → Ollama | `model`, `input` | |
| `EmbeddingResponse` | inbound ← Ollama | `model`, `embeddings: List<List<Float>>` | 2D — batch 가능성 대비 |

JSON key는 모두 camelCase. Wikipedia만 snake_case (`content_urls`) 그대로 둠.

---

## 7. Known Tech Debt (구체적 위치 + 권장 조치)

| # | 위치 | 문제 | 권장 조치 |
|---|---|---|---|
| TD-1 | `VectorIndex` (LSH off) | 기본 namespace별 brute-force cosine | `vector.index.lsh.enabled=true` 또는 외부 ANN(FAISS/pgvector) |
| TD-2 | pgvector 미연결 | prod는 plain PostgreSQL, 검색은 인메모리 인덱스 | `PgVectorStore` 구현 + `VectorStore` 빈 교체 |
| TD-3 | `OllamaService.RestTemplate` | timeout/retry 없음 | `RestTemplateBuilder.setConnectTimeout/.setReadTimeout` |
| TD-4 | `IngestionService` (동시성) | `loadAll → mutate → save` race | synchronization or single-writer queue |
| TD-5 | `CrossEncoderReranker` | Intel Mac에서 DJL 모델 로드 폴백 | GPU/ARM 환경 또는 외부 reranker 서비스 |
| TD-6 | `ArticleStore` (legacy) | 빈으로 남아 dead code | 제거 (4.4 절차) |
| TD-7 | governance hook | `OllamaService.generate()` 안에 묻혀 있음 | Spring AOP `@AfterReturning` 으로 분리하면 audit 누락 위험 더 줄어듦 |

---

## 8. 새 기능 추가 패턴 (cookbook)

### 8.1 새 REST endpoint 추가
1. `controller/` 에 새 컨트롤러 또는 기존 컨트롤러에 메서드 추가.
2. 비즈니스 로직은 반드시 `service/` 의 새 서비스/메서드로.
3. 요청/응답 모델은 `dto/` (외부 노출용) 또는 `data/` (도메인) 중 선택. 외부 API DTO ≠ 도메인 모델 분리 원칙 지킬 것.
4. `static/js/app.js` 에서 fetch 호출 추가 (대시보드 확장 시).
5. [API.md](./API.md) 업데이트.

### 8.2 새 LLM 모델 교체
```bash
ollama pull qwen3.6
OLLAMA_CHAT_MODEL=qwen3.6 ./mvnw spring-boot:run
```
- 기본 chat 모델은 `ibm/granite4:latest`, 화이트리스트는 `ollama.chat-models`. 화이트리스트 밖 모델은 거부됨 (`/api/rag/ask` 의 `model`).
- `think:false` 는 reasoning 토큰 비활성 옵션 — 다른 모델에 보내도 무시되긴 하나, 모델별 검증 권장.
- prompt 형식이 모델마다 다를 수 있음 (`RagService` 의 template 재실험).

### 8.3 Parquet schema 진화
[ERD.md 5 Schema Evolution](./ERD.md#5-schema-evolution-plan) 참조.

### 8.4 governance log에 새 필드 추가
1. `QueryLog` 에 필드 추가 + `@Column`.
2. `dev` 프로파일은 `create-drop` 이라 자동 반영. `demo`/`prod` 는 ddl-auto 정책에 따라 migration 필요.
3. `OllamaService.ask()` 에서 set.
