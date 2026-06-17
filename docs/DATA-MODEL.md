# MiniWatson — Data Model

The lifecycle of an `Article` from Wikipedia API to Parquet on disk.

---

## 1. Java Model — `Article`

```java
@Data
public class Article {
    private long id;
    private String namespace;        // multi-tenant 분리 키 (비면 "default")
    private String title;
    private String summary;
    private String url;
    private LocalDateTime ingestedAt;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private List<Float> embedding;   // 768-dim, nomic-embed-text
}
```

| Field        | Type            | Source                                           | Notes                             |
|--------------|-----------------|--------------------------------------------------|-----------------------------------|
| `id`         | `long`          | Generated (`store.size() + 1`)                   | Monotonic, not durable PK         |
| `namespace`  | `String`        | Ingest 시 지정 (tenant/project/collection)        | 비면 `"default"`, multi-tenant 키 |
| `title`      | `String`        | Wikipedia `title`                                | UTF-8                             |
| `summary`    | `String`        | Wikipedia `/page/summary` → `extract` field      | Plain text, no markup             |
| `url`        | `String`        | Wikipedia `content_urls.desktop.page`            | Canonical                         |
| `ingestedAt` | `LocalDateTime` | Server clock at ingest time                      | ISO-8601 when serialized          |
| `embedding`  | `List<Float>`   | `nomic-embed-text` over `summary`                | 768 floats, hidden from API       |

---

## 2. Anti-Corruption Layer — Wikipedia DTO

Wikipedia's JSON has dozens of fields you don't need. The internal DTO ignores them all:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public class WikipediaResponse {
    private String title;
    private String extract;
    private ContentUrls content_urls;
    // many other Wikipedia fields are silently dropped
}
```

**Why**: protects internal domain model from upstream schema churn. Same principle as DDD anti-corruption layer.

---

## 3. Avro Schema — `article.avsc`

```json
{
  "type": "record",
  "name": "Article",
  "namespace": "miniwatson.schema",
  "fields": [
    { "name": "id",         "type": "long" },
    { "name": "title",      "type": "string" },
    { "name": "summary",    "type": "string" },
    { "name": "url",        "type": "string" },
    { "name": "ingestedAt", "type": "string" },
    { "name": "embedding",  "type": { "type": "array", "items": "float" } }
  ]
}
```

### Why namespace ≠ Java package

`namespace: "miniwatson.schema"` is **intentionally non-Java**.

If you use `com.miniwatson.data`, Avro reflection tries to locate the class `com.miniwatson.data.Article` at read-time and cast `GenericRecord` to it, producing:

```
ClassCastException: com.miniwatson.data.Article
                   cannot be cast to org.apache.avro.generic.IndexedRecord
```

`miniwatson.schema.Article` doesn't exist as a class, so Avro safely returns `GenericRecord` and the application maps it to POJO explicitly.

---

## 4. Parquet Layout

| Property            | Value                              |
|---------------------|------------------------------------|
| Format              | Parquet (Apache 1.14.0)            |
| Schema source       | Avro (`article.avsc`)              |
| Compression         | SNAPPY                             |
| Row group size      | Default (128 MB)                   |
| Page size           | Default (1 MB)                     |
| Data model          | `GenericData.get()` (writer only)  |
| File path           | `./data/articles.parquet`          |
| Side-car            | `.articles.parquet.crc` (checksum) |

### Observed compression

| Storage         | Size (6 articles) | Ratio |
|-----------------|------------------:|------:|
| JSON (raw)      | 54.0 KB           | 1.0×  |
| Parquet+SNAPPY  |  7.8 KB           | 6.9×  |

The win comes mostly from columnar dictionary encoding on `embedding` (768 floats per row).

---

## 5. Read / Write APIs (`ArticleParquetStore`)

```java
public void saveAll(List<Article> articles) throws IOException {
    // 1. ensure ./data/ exists
    // 2. delete existing parquet (Parquet has no overwrite)
    // 3. AvroParquetWriter with GenericData.get() + SNAPPY
    // 4. iterate → GenericRecord.put(...) → writer.write(record)
}

public List<Article> loadAll() throws IOException {
    // 1. early-return [] if file missing
    // 2. AvroParquetReader (no withDataModel — Reader builder doesn't have it)
    // 3. iterate → map GenericRecord → POJO
    // 4. embedding: Object → List<?> → for each Number → float
}
```

### Why no `withDataModel` on the Reader

`ParquetReader.Builder` (parquet-avro 1.14.0) doesn't expose `withDataModel`. The namespace trick in section 3 is sufficient — Avro falls back to `GenericRecord` when no Java class matches the schema name.

`AvroParquetWriter.Builder` **does** expose `withDataModel`, and we pass `GenericData.get()` to be explicit on the write side.

---

## 6. Data Lifecycle

```
Wikipedia (REST)
   │  GET /api/rest_v1/page/summary/{title}
   │  + User-Agent: MiniWatson/1.0 (mailto:kdea989@gmail.com)
   ▼
WikipediaResponse (DTO, ignoreUnknown)
   │
   ▼
Article (POJO)  ◄── embedding via Ollama nomic-embed-text
   │
   ▼
GenericRecord (Avro)
   │
   ▼
articles.parquet (Parquet+SNAPPY, columnar)
   │
   ▼ (loadAll)
GenericRecord
   │
   ▼
Article (POJO)
   │
   ▼
RagService cosine similarity
```

---

## 7. Operational Notes

| Topic                  | Detail                                                                   |
|------------------------|--------------------------------------------------------------------------|
| Hadoop on Java 21      | Spring Boot plugin sets `-Djava.security.manager=allow`                  |
| Parquet overwrite      | Not supported by spec — `saveAll` deletes file first                     |
| CRC sidecar            | `.articles.parquet.crc` written by Hadoop FileSystem; safe to delete     |
| Backup convention      | `.backup` suffix is convention only — not part of the read path          |
| Embedding hidden       | `@JsonProperty(WRITE_ONLY)` keeps 768 floats out of API responses        |
| Anti-corruption        | `@JsonIgnoreProperties(ignoreUnknown=true)` on all Wikipedia DTOs        |

---

## 8. Production Evolution Path

| Today                          | Production                                       |
|--------------------------------|--------------------------------------------------|
| Single Parquet file rewrite    | Iceberg or Delta Lake — append-only, ACID        |
| 768-dim cosine in JVM          | pgvector / FAISS / Milvus                        |
| `ingestedAt` as string         | `timestamp-millis` logical type in Avro          |
| `id` from `size()+1`           | Snowflake ID or DB sequence                      |
| Single tenant                  | `tenantId` field + partitioned by tenant         |
| No schema evolution            | Avro forward/backward compat via schema registry |

---

## 9. Sample Article (Parquet → JSON projection)

```json
{
  "id": 1,
  "title": "Retrieval-augmented generation",
  "summary": "Retrieval-augmented generation (RAG) is a technique...",
  "url": "https://en.wikipedia.org/wiki/Retrieval-augmented_generation",
  "ingestedAt": "2026-06-05T00:48:40.411367",
  "embedding": "/* 768 floats — hidden from API */"
}
```

---

## 10. 스토리지 함정 (실제로 겪은 것)

티어드 스토리지(hot JSON → cold Parquet)를 붙이면서 드러난 실전 버그들. 기록용.

### 10.1 프레젠테이션 annotation이 영속성을 깨뜨림

`Article.embedding`에 `@JsonProperty(access = WRITE_ONLY)`를 붙여 **API 응답에서 768차원을 숨겼다.** 이건 Parquet만 쓸 땐 문제없었다 — Parquet writer는 Jackson이 아니라 getter로 값을 읽으니까.

그런데 **hot tier를 JSON(`ArticleStore`)으로** 만들자 같은 Jackson 직렬화가 hot 저장에도 쓰였다. WRITE_ONLY = "직렬화 출력 시 제외"라서 **hot JSON에 embedding이 안 저장됨.** 결과:

```
ingest → hot JSON 저장(embedding 누락)
→ threshold 도달 → compact() → hot JSON 재로딩(embedding=null)
→ Parquet은 embedding이 required → RuntimeException: Null-value for required field: embedding
```

**교훈:** *프레젠테이션용 annotation(API 숨김)을 영속성 모델에 같이 두면, 그 모델을 다른 경로로 직렬화할 때 조용히 깨진다.* 하나의 엔티티가 (1) 스토리지 모델, (2) API 응답 DTO 두 역할을 겸하면 생기는 결합 문제.

**해결:** hot tier 전용 ObjectMapper에 mixin을 줘서 embedding을 강제로 직렬화 포함 (API용 Spring 매퍼는 그대로 숨김 유지):
```java
objectMapper.addMixIn(Article.class, EmbeddingPersistMixin.class);
private abstract static class EmbeddingPersistMixin {
    @JsonProperty(access = JsonProperty.Access.READ_WRITE) List<Float> embedding;
}
```
근본적으로는 **스토리지 모델과 API 응답 DTO를 분리**하는 게 정석.

### 10.2 임베딩 모델은 지식베이스당 고정

저장된 벡터는 특정 임베딩 모델의 벡터 공간에 속한다. 모델을 바꾸면(예: nomic 768 → mxbai 1024) 기존 벡터와 새 질문 벡터가 **공간 불일치**(또는 차원 불일치)로 검색이 깨진다. chat 모델은 요청별 교체 가능하지만 **embedding 모델은 KB 불변 속성**이다. 모델별 비교가 목적이면 **namespace를 실험 버킷으로** 분리하라 (ns마다 다른 임베딩 모델).

### 10.3 손상/빈 Parquet은 기동을 막는다

compaction이 빈 결과를 쓰거나 쓰기가 중단되면 4바이트짜리 손상 Parquet이 생길 수 있고, 시작 시 `VectorIndex.hydrate()`가 이를 읽다 `RuntimeException`으로 앱이 안 뜬다. `loadAll()`에 파일 크기 가드(`length < 8 → 빈 리스트`)를 두거나 hydrate에서 `Exception`을 넓게 잡아 빈 인덱스로 시작하면 견고해진다.

### 10.4 작은 코퍼스에서 LSH는 엉뚱한 소스를 준다 (recall 문제)

문서 3개(Vector database / RAG / Wiki)만 있는 상태에서 "What is RAG?"를 물었더니, 정작 **RAG 문서가 소스에서 빠지고 Wiki가 잡혔다.** RAG 문서는 KB에 분명히 있었는데도.

**원인:** LSH는 **근사(approximate)** 검색이다. 질문 벡터의 해시 버킷에 진짜 최근접 문서(RAG)가 없으면 **후보 집합에서 아예 제외**되고, 코드가 후보들만 exact-cosine 정렬하니 후보에 없는 RAG는 절대 못 뽑힌다. 대신 같은 버킷에 우연히 들어온 Wiki가 올라온다. 게다가 fallback은 "후보 **수** < topK"일 때만 전수 스캔으로 전환하지, "후보가 **틀렸을 때**"는 안 걸린다 → **자신만만하게 틀린 결과**.

문서가 적으면 16비트 해시 버킷이 듬성듬성해서 이런 miss(낮은 recall)가 흔하다.

**해결/원칙:**
- **작은 코퍼스 → brute-force(`vector.index.lsh.enabled=false`).** 전수 cosine이라 항상 정확하고, n이 작으니 비용도 무의미.
- **LSH는 수만~수십만 건 이상에서만** 의미. 속도를 위해 정확도(recall)를 희생하는 게 ANN의 본질.

> 교훈: *ANN(LSH)은 "빠르지만 가끔 틀림". 코퍼스가 작을 땐 brute-force가 더 정확하고 충분히 빠르다.* 데모/소규모는 brute-force를 기본으로, LSH는 대규모 옵션으로 문서화하는 게 맞다.

### 10.5 임베딩 모델의 task 프리픽스 (nomic)

LSH를 꺼서 brute-force(전수 정확검색)로 돌렸는데도 "What is RAG?"가 RAG 대신 Wiki를 줬다. 범인은 인덱스가 아니라 **임베딩 입력 형식**이었다.

`nomic-embed-text`는 **task 프리픽스를 붙여 학습된** 모델이다. 프리픽스 없이 raw 텍스트를 임베딩하면 벡터 품질이 떨어져 엉뚱한 문서가 더 가깝게 나온다. 규칙:
- 문서: `search_document: <내용>`
- 질문: `search_query: <질문>`

**결정적 포인트 — 짝을 맞춰야 한다.** 한쪽(질문)만 프리픽스를 붙이면 질문은 query 공간, 문서는 raw 공간에 있어 **오히려 더 어긋난다.** 둘 다 규칙을 따라야 같은 공간에서 비교된다.

```java
// 문서 (IngestionService 전 경로)
embeddingService.embed("search_document: " + text);
// 질문 (RagService)
embeddingService.embed("search_query: " + question);
```

프리픽스를 바꾸면 **기존 벡터를 재생성**해야 한다 (저장된 벡터는 옛 방식이라 새 질문 벡터와 안 맞음). 즉 parquet/json 비우고 재-ingest.

> 교훈: *임베딩 모델마다 요구하는 입력 형식이 있다. 안 지키면 인덱스가 정확(brute-force)해도 retrieval이 망가진다. "검색 품질이 이상하면 인덱스보다 임베딩 입력부터 의심하라."* (모델마다 다름 — granite-embedding류는 프리픽스가 불필요한 경우도 있으니 모델 카드 확인.)

---

## 11. 문서 카탈로그 (catalog/data 분리) — 구현됨

청킹 도입으로 한 문서가 청크 N개(Article N개)로 흩어졌다. 데이터(벡터·본문)는 Parquet/JSON에 그대로 두되, **문서 단위 메타데이터만 H2에 미러링**해 SQL로 조회·집계할 수 있게 했다. 이것이 lakehouse의 catalog/data 분리이고, watsonx.data의 카탈로그 개념에 해당한다.

### 왜 분리하나

- 데이터(대량 벡터)는 컬럼형 Parquet에 — 압축·스캔 효율.
- 카탈로그(가벼운 메타)는 H2에 — SQL 조회·집계·거버넌스 분석.
- 둘을 한 곳에 섞지 않는다. Parquet이 source of truth, H2 카탈로그는 거기서 파생된 뷰.

### 스키마 — `document_catalog`

| 컬럼 | 의미 |
|---|---|
| id | PK |
| title | baseTitle (청크 번호 제거) |
| namespace | 테넌트 |
| source_type | wikipedia / image / file |
| chunks | 청크 수 |
| embed_model | 어떤 임베더로 만들었나 |
| ingested_at | 최초 적재 시각 |

### 쓰기 경로 (dual-write)

ingest가 끝나면 `(title, namespace)` 기준으로 카탈로그를 upsert한다. 이미 있으면 chunks만 갱신, 없으면 새 행. 삭제(`deleteDocument`) 시 청크를 지운 뒤 카탈로그 행도 제거한다.

### 시작 시 재구성 (@PostConstruct hydrate)

H2(dev)는 in-memory라 재시작하면 카탈로그가 빈다. 반면 Parquet은 남는다. 그래서 시작 시 `VectorIndex.hydrate`와 같은 패턴으로 Parquet의 Article을 읽어 `ns + baseTitle`로 그룹핑해 카탈로그를 재구성한다. source_type은 url 스킴으로 추정한다(`image://`, `file://`, 그 외 wikipedia).

핵심 원칙: **영속 데이터(Parquet)가 진실, H2 카탈로그는 파생.** 재시작 시 파생을 다시 만들면 정합성이 유지된다. 벡터 인덱스도 동일한 철학(hydrate)이라 일관된다.

### H2 console 활용

카탈로그가 생기면서 H2 console로 지식베이스 자체를 SQL로 분석할 수 있다.

```sql
-- 소스 타입별 문서/청크 수
SELECT source_type, COUNT(*) docs, SUM(chunks) total_chunks
FROM document_catalog GROUP BY source_type;

-- 테넌트별 문서 수
SELECT namespace, COUNT(*) docs FROM document_catalog GROUP BY namespace;

-- 청크가 가장 많은 문서
SELECT title, namespace, chunks FROM document_catalog ORDER BY chunks DESC LIMIT 10;

-- 특정 임베더로 만든 문서 (임베더 교체 시 재인덱싱 대상 식별)
SELECT title, namespace, embed_model FROM document_catalog WHERE embed_model <> 'nomic-embed-text';
```

### 한계 (정직하게)

- dual-write라 카탈로그와 Parquet이 일시적으로 어긋날 수 있다. 재시작 hydrate가 보정하지만, 런타임 중 부분 실패는 보정 안 된다 → 프로덕션은 트랜잭션 경계나 주기적 reconciliation이 필요.
- `embed_model`은 ingest 시점 설정값을 기록한다. 실제 사용 모델과 설정이 다르면 부정확 → EmbeddingService가 실제 모델명을 노출하면 더 정확.

### 11.1 함정: Spring Boot 4에서 javax.annotation은 무시된다

카탈로그 hydrate(@PostConstruct)가 시작 시 안 돌아 카탈로그가 계속 비었다. 원인은 `import javax.annotation.PostConstruct`였다. Spring Boot 3/4는 Jakarta EE라 `jakarta.annotation.PostConstruct`만 인식한다. javax 쪽을 쓰면 컴파일은 되더라도 Spring이 라이프사이클 콜백으로 호출하지 않아, 메서드가 조용히 안 불린다.

```java
import jakarta.annotation.PostConstruct;   // O
// import javax.annotation.PostConstruct;  // X — Spring Boot 4에서 호출 안 됨
```

교훈: 콜백/어노테이션이 "조용히 안 도는" 증상이면 javax vs jakarta 네임스페이스부터 의심한다. (기존에 잘 돌던 VectorIndex.hydrate는 jakarta로 import돼 있었다.)

---

## 12. OLTP vs OLAP — 저장소 선택의 기준

MiniWatson은 두 종류의 저장소를 쓴다. 왜 하나로 안 하고 나누는지의 근거가 OLTP/OLAP 구분이다.

### 정의

- OLTP (Online Transaction Processing): 행 단위의 잦은 읽기/쓰기. 주문, 로그 적재, 단건 조회. 정합성·낮은 지연이 중요. 예: Postgres, H2.
- OLAP (Online Analytical Processing): 대량 행을 집계·스캔하는 분석 쿼리. 리포팅, 추세 분석. 컬럼형 저장 + 압축이 중요. 예: Parquet/lakehouse, Redshift, BigQuery, Snowflake.

### 장단점

| 구분 | OLTP (행 지향) | OLAP (열 지향) |
|---|---|---|
| 강점 | 단건 insert/update/조회 빠름, 트랜잭션·정합성 | 대량 집계·스캔 빠름, 높은 압축률 |
| 약점 | 대량 분석 스캔 느림, 압축 낮음 | 단건 쓰기 느리고 비쌈, 트랜잭션 약함 |
| 적합 | 감사 로그 적재, 카탈로그, 실시간 조회 | 기간별 집계, BI, 대규모 추세 |

### MiniWatson의 적용

- query_log(감사), document_catalog(메타) -> H2/Postgres (OLTP). 호출마다 행 하나씩 insert되고 단건/소량 조회가 많다. 행 지향이 맞다.
- Article + embedding(콜드 티어) -> Parquet (OLAP/lakehouse). 대량 벡터를 압축 저장하고 스캔한다. 열 지향이 맞다. watsonx.data가 Parquet을 native 포맷으로 쓰는 이유와 같다.

즉 tiered storage(10절)의 hot/cold 분리는 사실상 OLTP(JSON/H2 hot)와 OLAP(Parquet cold)의 분리이기도 하다.

### 규모가 커지면

감사 로그가 수억 건이 되면, 운영 DB(OLTP)에서 받되 장기 보관·대규모 분석은 웨어하우스(OLAP: Redshift/BigQuery/Snowflake)나 lakehouse로 ETL해 옮긴다. Redshift 같은 웨어하우스에 행을 하나씩 insert하는 것은 안티패턴이다(배치 적재·대량 스캔에 최적화돼 있어 단건 쓰기가 느리고 비싸다). 현재 규모에서는 불필요하므로 도입하지 않는다 — 개념만 남긴다.

교훈: "한 DB로 다 하려" 하지 말고, 워크로드(트랜잭션 vs 분석)에 맞는 저장소를 고른다. 잘못 고르면(예: 로그를 웨어하우스에 단건 insert) 느리고 비싸진다.
