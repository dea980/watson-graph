# MiniWatson — SWE Handover Guide

> 다음 엔지니어가 처음 코드베이스를 받았을 때 30분 안에 전체 그림을 이해하고 1시간 안에 로컬에서 돌리고 1일 안에 안전하게 수정/배포할 수 있도록 작성된 인수인계 문서.

---

## 0. 이 문서 패키지의 구성

| 문서 | 용도 | 누가 읽나 |
|---|---|---|
| **HANDOVER.md** (이 파일) | 전체 entry point, onboarding 순서 | **모든 신규 SWE 1순위** |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | 시스템 아키텍처 · 레이어 · 데이터 흐름 다이어그램 | 설계 이해 |
| [SDS.md](./SDS.md) | Software Design Specification · 모듈/클래스 명세 | 코드 수정 전 |
| [ERD.md](./ERD.md) | 데이터 모델 · DB 스키마 · Parquet/Avro/JPA | 데이터 관련 작업 |
| [API.md](./API.md) | REST API 레퍼런스 · 요청/응답 스펙 | 프론트/외부 연동 |
| [DEBUGGING.md](./DEBUGGING.md) | 디버깅 포인트 · 알려진 함정 · 트러블슈팅 | 장애/에러 발생 시 |

---

## 1. 프로젝트 한 줄 요약

**IBM watsonx의 3-layer 구조 (data · ai · governance) 를 Spring Boot + Ollama + Parquet 로 미니어처화한 로컬 RAG 시스템.**

- **목적**: enterprise GenAI platform이 실제로 어떻게 동작하는지 코드로 이해.
- **상태**: roadmap 1~22 (코어 + RAG 고도화 + governance + multimodal + Postgres prod) 완료. 정형 표(CSV/XLSX)는 DuckDB text-to-SQL(`/api/tabular`)로 분리 — 비정형은 RAG, 집계는 SQL. 배포 노트/PgVectorStore 진행 중. `roadmap` 참조 (README.md).
- **작성자**: Daeyeop Kim (`kdea989@gmail.com`)

---

## 2. 30분 onboarding 코스

순서대로 따라가면 됩니다.

### Step 1 — 큰 그림 (5분)
1. [README.md](../README.md) 의 `Architecture` 섹션 (line 12–46) 다이어그램 본다.
2. [ARCHITECTURE.md](./ARCHITECTURE.md) `1. Layered View` 까지 읽는다.

### Step 2 — 핵심 흐름 2개 추적 (15분)

**Flow A: Ingestion (지식 수집)**
```
User → POST /api/data/ingest?title=RAG
     → DataController.ingest()
     → IngestionService.ingest()
         → Wikipedia REST API call
         → EmbeddingService.embed() — nomic-embed-text → 768-dim vector
         → ArticleParquetStore.save() — Parquet append
     → Article JSON 반환
```

**Flow B: RAG Q&A**
```
User → POST /api/rag/ask {"question": "..."}
     → RagController.ask()
     → RagService.ask()
         → EmbeddingService.embed(question)
         → HybridRetriever — vector + BM25, RRF 융합 → 후보 top-N (FETCH_N=20)
         → Reranker (none/llm/mmr/cross) → 최종 top-K (=2)
         → augmented prompt 조립 + sources "#id title" 생성
         → OllamaService.ask(prompt, model, question, sources) — granite4 기본, think:false
             → PII 마스킹 → QueryLogRepository.save(log)   ← governance 자동 audit
     → {answer, sources[]} 반환
```

두 흐름의 실제 코드는 [SDS.md 3 Sequence Diagrams](./SDS.md#3-sequence-diagrams) 에 line-by-line 매핑되어 있습니다.

### Step 3 — 로컬 실행 (10분)
[README.md `Quick Start`](../README.md#quick-start) 그대로 따라하면 됩니다. 주의점:

| Gotcha | 조치 |
|---|---|
| `ollama serve` 가 별도 터미널에서 떠 있어야 함 (11434 포트) | `lsof -i :11434` 로 확인 |
| `ibm/granite4:latest`, `granite-embedding:278m` 모델 둘 다 `ollama pull` 필요 | `ollama list` 로 확인 |
| Java 21 (Hadoop SecurityManager 호환) 필요 | `pom.xml` 가 `-Djava.security.manager=allow` 자동 부여 |
| `./mvnw spring-boot:run` 첫 실행은 의존성 다운로드 1~3분 | 정상 |

확인: `curl http://localhost:8080/api/rag/models` → `{ default, available[] }` 반환

---

## 3. 코드베이스 지도 (1-page)

```
miniwatson/
├── docs/                              ← 본 인수인계 문서 (NEW)
│   ├── HANDOVER.md                    ← 너 지금 여기
│   ├── ARCHITECTURE.md
│   ├── SDS.md
│   ├── ERD.md
│   ├── API.md
│   └── DEBUGGING.md
│
├── src/main/java/com/miniwatson/
│   ├── MiniwatsonApplication.java     ← Spring Boot 엔트리
│   ├── controller/                    ← 5개 REST 컨트롤러 (Rag/Data/Multimodal/Governance/Tabular)
│   ├── service/                       ← 비즈니스 서비스 (ingest/embed/ocr/retrieve/rerank/chunk + tabular SQL)
│   ├── data/                          ← Article 도메인 + Parquet I/O
│   ├── governance/                    ← QueryLog JPA entity + repo
│   └── dto/                           ← Ollama/Embedding/Ask 요청/응답
│
├── src/main/resources/
│   ├── application.yaml               ← 공통 + profile 선택
│   ├── application-{dev,demo,prod}.yaml
│   ├── article.avsc                   ← Parquet schema (Avro)
│   └── static/                        ← index.html + css + js (Carbon-style)
│
├── data/                              ← 런타임 상태 (gitignored)
│   ├── articles.json                  ← hot tier (recent appends)
│   ├── articles.parquet               ← cold tier (compacted)
│   └── .articles.parquet.crc          ← Hadoop checksum (자동)
│
├── pom.xml                            ← Maven (Spring Boot 4.0.6, Java 21)
└── README.md
```

크기 감각:
- Java 소스: ~36 파일 (controller/service/data/governance/dto)
- 의존성: Spring Boot 4 + JPA + Parquet/Avro + Hadoop + Lombok + Jackson + Tika + DJL
- 외부 시스템 의존: Ollama(localhost:11434), Wikipedia REST API

---

## 4. 인수인계 시 우선 점검 체크리스트

신규 SWE가 처음 받았을 때 확인하세요.

- [ ] `ollama list` — `ibm/granite4:latest`, `granite-embedding:278m` 둘 다 존재?
- [ ] `./mvnw clean compile` — 클린 빌드 성공?
- [ ] `./mvnw spring-boot:run` 후 `curl http://localhost:8080/api/rag/models` 응답에 default/available 들어옴?
- [ ] `curl -X POST "http://localhost:8080/api/data/ingest?title=RAG"` 성공? (Article JSON 반환)
- [ ] `curl http://localhost:8080/api/data/articles` 가 ingest한 article 보여줌?
- [ ] `curl -X POST http://localhost:8080/api/rag/ask -H 'Content-Type: application/json' -d '{"question":"What is RAG?"}'` 응답 들어옴?
- [ ] `curl http://localhost:8080/api/governance/logs` 에 위 질문 기록됨?
- [ ] http://localhost:8080 (대시보드) 가 브라우저에서 뜸?
- [ ] `data/articles.parquet` 파일 존재?

위 모두 통과하면 시스템이 정상이라는 뜻이고, 그렇지 않으면 [DEBUGGING.md](./DEBUGGING.md) 의 `Common Failures` 를 매칭해서 봅니다.

---

## 5. "지금 당장 손대지 마라" 영역

특정 부분은 미묘한 trade-off가 있으니 함부로 리팩토링하지 마세요. 자세한 이유는 [DEBUGGING.md 5 Landmines](./DEBUGGING.md#5-landmines--dont-touch-without-reading) 에 있습니다.

| 위치 | 왜 만지면 안 되는가 |
|---|---|
| `pom.xml` `<jvmArguments>-Djava.security.manager=allow</jvmArguments>` | Java 21 + Hadoop 호환 필수. 빼면 부팅 실패 |
| `OllamaRequest.think = false` | gemma3/4 reasoning 토큰이 num_predict 예산 잡아먹어 latency 3× 증가 |
| `IngestionService` User-Agent 헤더 | Wikipedia REST는 User-Agent 없으면 403 |
| `Article.embedding` `@JsonProperty(WRITE_ONLY)` | 768-dim float 배열을 API 응답에서 숨김. 빼면 응답 50KB+ |
| `ArticleParquetStore.saveAll()` 의 "기존 파일 삭제 후 재작성" | Parquet은 in-place update 불가능. 의도된 동작 |
| `WikipediaResponse` 의 inner class들 `static` | non-static 이면 Jackson 역직렬화 실패 |

---

## 6. 변경 시 권장 프로세스

1. **이슈/PR 만들기 전에**: [ARCHITECTURE.md](./ARCHITECTURE.md) 의 해당 레이어 섹션 + [SDS.md](./SDS.md) 의 해당 클래스 명세 본다.
2. **DB/Parquet 스키마 변경**: [ERD.md](./ERD.md) 의 evolution 절차 따라가기.
3. **API 변경**: [API.md](./API.md) 도 같이 업데이트. 프론트 (`static/js/app.js`) 동시 수정.
4. **빌드/실행 깨질 가능성 있는 변경**: [DEBUGGING.md](./DEBUGGING.md) 의 startup checklist 다시 돌려보기.
5. **commit message**: `feat:`, `fix:`, `refactor:`, `docs:` 등 Conventional Commits 사용 (기존 history 와 동일).

---

## 7. 외부 의존성 cheatsheet

| 의존성 | 주소 / 버전 | 어디서 쓰임 | 끊기면? |
|---|---|---|---|
| Ollama daemon | `localhost:11434` | `OllamaService`, `EmbeddingService` | Ask/RAG/Ingest 전부 실패 |
| ibm/granite4:latest (chat model) | `ollama pull ibm/granite4:latest` | `OllamaService.ask()` | RAG 답변 생성 실패 |
| granite-embedding:278m (embedding) | `ollama pull granite-embedding:278m` | `EmbeddingService.embed()` | Ingest + RAG retrieval 실패 |
| Wikipedia REST | `https://en.wikipedia.org/api/rest_v1/page/summary/{title}` | `IngestionService` | Ingest 실패 (기존 데이터는 OK) |
| H2 DB | in-memory(dev) / file(demo) | governance audit log | 부팅 실패 |
| Parquet file | `./data/articles.parquet` | 지식 베이스 | RAG `No articles in knowledge base` 에러 |

---

## 8. 연락처 & 컨텍스트

- **원작자**: Daeyeop Kim (`kdea989@gmail.com`, [github.com/dea980](https://github.com/dea980))
- **목적**: IBM Consulting 인턴십 준비용 — watsonx 3-layer 구조를 실제로 짜본 학습 프로젝트.
- **상용 사용 의도 없음**: 학습용/포트폴리오.
- **구현 완료**: vector index(in-memory), hybrid search(vector+BM25 RRF), reranking(none/llm/mmr/cross),
  chunking(fixed/recursive/semantic), multimodal(vision+OCR), PII redaction, governance stats/feedback,
  multi-tenant namespacing, document catalog, eval harness, tabular text-to-SQL(DuckDB), Postgres prod 프로필. (README roadmap 1~22 참조)
- **벡터 검색 기본값은 brute-force cosine** — LSH는 `vector.index.lsh.enabled=true` 로 opt-in. 의도된 config 선택이지 미구현 아님.
- **남은 gap**:
  - PgVectorStore — pgvector 컨테이너는 떠 있으나 벡터 검색은 아직 in-memory VectorIndex
  - 보안/인증, tenant isolation 강제, audit 저장 실패 격리(try/catch)

---

## 9. 인수인계 완료 기준

다음을 본인이 직접 수행할 수 있어야 인수인계 완료라고 봅니다.

1. [x] 로컬에서 빌드/실행/dashboard 접속까지 한 번에.
2. [x] Wikipedia article 1개를 ingest 하고 RAG 질문을 던져 governance log까지 확인.
3. [x] Parquet 파일이 어떻게 생기고 schema는 `article.avsc` 라는 것을 설명할 수 있다.
4. [x] "`OllamaRequest.think=false` 를 왜 두었나?" 같은 질문에 답할 수 있다.
5. [x] 새 API endpoint를 추가하는 절차 (controller → service → DTO) 를 알고 있다.
6. [x] 장애 발생 시 [DEBUGGING.md](./DEBUGGING.md) 의 어느 섹션을 봐야 하는지 안다.

**완료되면 이 파일 맨 아래에 인수일자/인수자 sign-off 한 줄 추가하기를 권장.**

---

### Sign-off

| 일자 | 인수자 | 비고 |
|---|---|---|
| 2026-06-05 | Daeyeop Kim (작성) | initial handover docs |
| | | |
