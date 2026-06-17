# pgvector (영속 Vector Store)

인메모리 VectorIndex와 같은 `VectorStore` 계약을 구현하되 저장소를 Postgres+pgvector로 바꾼 구현(PgVectorStore). 디스크 영속이라 재시작 후 재인덱싱이 필요 없고, 수십만+ 벡터로 확장된다. 인메모리는 차원 무관·즉시지만 재시작마다 parquet에서 재적재한다(DECISIONS.md 5절).

## 1. 왜 / 언제

| 상황 | 선택 |
|---|---|
| 개발·소량·차원 실험(384/768/1024 갈아끼움) | memory (VectorIndex) |
| 영속·대규모·운영, 재시작 잦음 | pgvector (PgVectorStore) |

차원 고정이 핵심 제약이다. pgvector 컬럼은 `vector(768)`라 768 임베더(granite-embedding:278m, nomic)만 적재된다. 384/1024(granite-30m, mxbai)는 인메모리 전용. 그래서 임베딩 모델 비교는 인메모리에서 하고, 승자(768)만 pgvector로 운영 검증한다.

## 2. 전략 패턴 — 코드 변경 0

`HybridRetriever`는 `VectorStore` 인터페이스 하나만 주입받는다. 구현체 선택은 설정으로 한다.

```
VectorStore (interface: add / rebuild / search / stats)
 ├─ VectorIndex    @ConditionalOnProperty(vector.store=memory, matchIfMissing=true)  [기본]
 └─ PgVectorStore  @ConditionalOnProperty(vector.store=pgvector)
```

`vector.store=pgvector`면 PgVectorStore만, 아니면 VectorIndex만 빈으로 뜬다. 둘이 동시에 뜨면 주입이 모호해지므로 VectorIndex에 `matchIfMissing=true`로 기본을, PgVectorStore에 명시 조건을 걸어 상호 배타로 만들었다. 검색/적재 호출부는 어느 쪽이 떠도 동일하다.

주의(전략 패턴 누수): 이 구조가 성립하려면 **주입을 받는 모든 곳이 구체 타입이 아니라 인터페이스 `VectorStore`로 받아야** 한다. 한 곳이라도 `VectorIndex`(구체)를 주입받으면 pgvector 모드에서 그 빈이 없어 기동이 깨진다(실제로 IndexingService가 그랬다 — [DEBUGGING.md 4.6](DEBUGGING.md)). 점검: `grep -rn "VectorIndex " src/main`.

## 3. 독립 커넥션 (멀티 데이터소스 회피)

PgVectorStore는 `TabularSqlService`(DuckDB)와 같이 자체 JDBC 커넥션을 직접 연다(`DriverManager.getConnection`). 그래서 감사로그/카탈로그용 H2+JPA 데이터소스를 건드리지 않는다. Spring에 두 번째 DataSource를 등록하면 `@Primary`·분리 설정이 번지므로, 벡터 저장소만 독립적으로 붙이는 게 단순하고 안전하다.

대가: 단일 커넥션 + `synchronized`(인메모리 VectorIndex도 동일)라 동시성은 demo 수준이다. 운영에서 처리량이 필요하면 HikariCP 풀로 교체한다.

## 4. 스키마 / 검색

부팅 시 `ensureSchema()`가 멱등 DDL을 실행한다.

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS article_vectors (
    id BIGSERIAL PRIMARY KEY, namespace TEXT, title TEXT, summary TEXT,
    url TEXT, ingested_at TIMESTAMP, embedding vector(768));
CREATE INDEX IF NOT EXISTS idx_av_ns   ON article_vectors (namespace);
CREATE INDEX IF NOT EXISTS idx_av_hnsw ON article_vectors USING hnsw (embedding vector_cosine_ops);
```

검색은 코사인 거리 연산자 `<=>`로 namespace 안에서 topK를 뽑는다.

```sql
SELECT ..., embedding::text FROM article_vectors
WHERE namespace = ? ORDER BY embedding <=> ?::vector LIMIT ?;
```

- `<=>` = 코사인 거리(작을수록 가까움). `vector_cosine_ops` HNSW 인덱스와 짝. 임베딩이 정규화돼 있으면 코사인이 의미상 맞다.
- `WHERE namespace`로 테넌트 격리(인메모리의 per-namespace 인덱스와 동일 효과).
- `embedding::text`를 같이 읽어 `List<Float>`로 복원한다. MMR rerank가 청크 임베딩끼리 cosine을 계산하므로 검색 결과 Article에 임베딩이 채워져 있어야 한다.

### HNSW vs IVFFlat

HNSW를 기본으로 골랐다. 그래프 기반이라 IVFFlat보다 recall/지연 트레이드오프가 좋고, IVFFlat처럼 적재 후 `lists` 튜닝·재빌드가 필요 없다. 대신 빌드가 느리고 메모리를 더 쓴다. 코퍼스가 수백만+로 커지고 적재 속도가 문제면 IVFFlat을 검토한다.

### 인메모리 대비 recall 갭 (해결됨 — 재구성 Article이 RRF 키 `id`를 잃었다)

실측: 같은 코퍼스·임베더·쿼리인데 라이브 recall이 인메모리(none/mmr 35/35)보다 낮았다(pgvector none 26/35, mmr 23/35). 원인 규명에 시간이 걸렸는데, 큰 가설을 하나씩 측정으로 배제한 끝에 진짜 원인을 찾았다. 디버깅 순서를 기록으로 남긴다 — "그럴듯한 원인"이 아니라 "측정으로 남은 원인"을 따라가는 게 핵심이었다.

배제된 가설(측정으로 반증):

- **ANN 근사 아님.** `EXPLAIN`이 HNSW가 아니라 `Bitmap Index Scan(idx_av_ns) → exact Sort(<=>)`. 442행이라 플래너가 exact 정렬을 택해 `ef_search`/`iterative_scan`은 이 규모엔 무관.
- **float4 vs double 정밀도 아님.** pg 결과를 앱에서 double 코사인으로 재정렬해도 한 case도 안 바뀜 → float4 순서 == double 순서.
- **저장 라운드트립 손실 아님.** `SELECT '[0.123456789]'::vector::text` → `[0.12345679]` (float4 전 자리수 유지). pg 벡터 = parquet 원본.
- **stale/좀비 아님.** 빌드 마커 로그로 최신 코드 확인, pg는 `down -v` 후 재적재.

진짜 원인: **`HybridRetriever.rrf()`가 후보를 `Article.id`로 식별**한다(`byId.put(a.getId(), a)`, `score.merge(a.getId(), ...)`). 그런데 PgVectorStore가 행에서 **재구성한** Article은 `id`를 안 채웠다. `id`는 primitive `long`이라 기본값 **0** — 결과적으로 pg의 모든 벡터 후보가 `id=0` 한 키로 **붕괴**해 RRF가 벡터 순위를 통째로 잃었다. 인메모리 VectorIndex는 인덱스에 보관된 **원본 Article**(진짜 id)을 반환하므로 멀쩡했다. 이래서 두 store가 갈렸고, double 재정렬이 무효였던 것(리스트 순서를 바꿔도 RRF가 뒤에서 0으로 뭉갬)까지 일관되게 설명된다.

수정: `article_vectors`에 `article_id` 컬럼을 두어 적재 시 원본 id를 저장하고, 검색 시 `a.setId(rs.getLong("article_id"))`로 복원한다. 그러면 RRF 키가 keyword 인덱스와 매칭돼 인메모리와 **동일하게** 작동 → 라이브 recall **35/35 회복**(인메모리 패리티).

교훈: **DB 행에서 도메인 객체를 재구성할 때는 식별 키 필드를 반드시 복원하라.** 그러지 않으면 검색 자체는 맞아도 후처리(RRF·dedup·조인)가 조용히 깨진다. 단위테스트로는 안 잡히고(벡터·코사인은 정상), 후처리까지 포함한 통합 경로에서만 드러난다. 부가 코드(`tuneRecall`의 ef_search/iterative_scan, `search()`의 coarse+double-rerank)는 이 갭의 원인은 아니었지만, 대규모로 키워 HNSW를 쓰게 될 때를 위한 정당한 방어라 유지한다.

## 5. 하이드레이션 = 콜드 스토어가 진실의 원천

부팅 시 테이블이 비어 있으면 `ArticleRepository.loadAll()`(parquet)에서 1회 적재한다. 이미 차 있으면 건너뛴다 — 이게 "재시작 후 재인덱싱 불필요"를 실증한다(인메모리는 매 부팅 rebuild). 새 ingest는 `IndexingService.index()`가 활성 VectorStore의 `add()`를 부르므로 자동으로 pgvector에도 들어간다.

차원 가드: `add()`가 768이 아닌 벡터는 경고 후 건너뛴다. 인덱스에 차원이 섞이면 검색이 깨지므로 적재 단계에서 막는다.

## 6. 검증 절차 (인메모리와 패리티)

```bash
# 1) Postgres+pgvector 기동
docker compose up -d
docker compose ps            # postgres가 healthy 될 때까지

# 2) pgvector 모드로 앱 기동 (임베더는 기본값 granite-embedding:278m=768)
VECTOR_STORE=pgvector ./mvnw spring-boot:run
#   로그: "[PgVectorStore] hydrated from cold store: N vectors"  (parquet에서 1회 적재)

# 3) 다른 터미널 — 측정 (재적재 불필요, parquet 데이터가 이미 768)
curl -s localhost:8080/api/data/index/stats | python3 -m json.tool   # mode=pgvector, dimension=768
python3 eval/run_eval.py     # 기대: 인메모리와 동일 35/35 (mmr 100%)

# 4) 영속성 실증: 앱 끄고 다시 pgvector로 기동
#    로그: "already populated, skip rehydrate" → eval 여전히 35/35 (재인덱싱 없이)
```

검증 결과(실측): 인메모리 none/mmr **35/35**, pgvector none/mmr **35/35** — 패리티 달성. 단, 처음엔 pgvector가 26/35로 떨어졌고 원인은 재구성 Article의 `id` 누락이었다(위 4절 "해결됨"). 패리티가 안 맞으면 (a) 차원 적재 누락, (b) **재구성 객체의 식별 키(id) 누락 → RRF 붕괴**, (c) namespace 매핑 불일치, (d) HNSW 근사 손실(대규모일 때) 순으로 본다. RagService의 `Retrieved N articles: [...]` 로그를 두 모드에서 대조하는 게 가장 빠른 격리법.

## 7. 종료 / 초기화

```bash
docker compose down       # 컨테이너만 정지(볼륨 유지 = 데이터 보존)
docker compose down -v    # 볼륨까지 삭제(빈 DB로 리셋)
```
