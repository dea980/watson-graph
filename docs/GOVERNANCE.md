# MiniWatson — Governance & Audit

Every RAG call is logged. This is MiniWatson's small-scale parity for **watsonx.governance**.

---

## 1. Why a Governance Layer?

LLM applications are unauditable by default. If a customer asks "what model gave me this answer, on what data, at what time?", you must answer in seconds.

MiniWatson treats every RAG call as a **regulated event**:

- **What** was asked (`question`, PII redacted)
- **What** came back (`answer`, PII redacted)
- **Which** sources grounded it (`sources`)
- **Which** model produced the answer (`model`)
- **How long** it took (`latencyMs`)
- **How much** PII was masked (`piiCount`)
- **When** (`createdAt`)

This is the minimum schema to satisfy:
- EU AI Act Art. 12 (record-keeping)
- ISO/IEC 42001 (AI management system audit trail)
- SOC 2 CC7 (system monitoring)
- IBM internal "AI usage transparency"

---

## 2. Schema — `QueryLog` (`query_log`)

> 실제 구현 엔티티는 `QueryLog`이고 테이블은 `query_log`다. H2 console 쿼리는 6절을 참조한다.


```java
@Entity
@Table(name = "query_log")
public class QueryLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String question;        // PII 마스킹됨
    @Column(columnDefinition = "TEXT")
    private String answer;          // PII 마스킹됨
    @Lob
    private String augmentedPrompt; // LLM에 실제 들어간 증강 프롬프트
    private String model;           // "ibm/granite4:latest"
    private Long latencyMs;
    private int piiCount;           // 이 질의에서 마스킹된 PII 건수
    @Column(columnDefinition = "TEXT")
    private String sources;         // 근거 청크 "#id title; ..." (RAG만)
    private String feedback;        // "up" | "down" | null
    private LocalDateTime createdAt; // @PrePersist
}
```

| Column           | Type        | Purpose                          |
|------------------|-------------|----------------------------------|
| id               | bigint PK   | Auto-increment                   |
| question         | TEXT        | User input (PII redacted)        |
| answer           | TEXT        | Answer (PII redacted)            |
| augmented_prompt | CLOB        | Prompt actually sent to the LLM  |
| model            | varchar     | Foundation model name            |
| latency_ms       | bigint      | End-to-end latency               |
| pii_count        | int         | PII matches masked in this call  |
| sources          | TEXT        | Provenance chunks (RAG only)     |
| feedback         | varchar     | up / down / null                 |
| created_at       | timestamp   | Set on persist                   |

---

## 3. Storage

| Profile | Backend                | Retention      | Notes                             |
|---------|------------------------|----------------|-----------------------------------|
| `dev`   | H2 in-memory           | per-JVM session | Reset on restart                  |
| `demo`  | H2 file (`./data/h2/`) | per-laptop      | Survives restart                  |
| `prod`  | PostgreSQL / Cloudant  | 13 months       | Reserved (placeholder)            |

---

## 4. Write Path

감사 로깅은 별도 서비스가 아니라 `OllamaService.generate(...)` 안에서 LLM 호출 직후에 일어난다. 모든 LLM 호출(ask / RAG / multimodal)이 이 한 곳을 지나므로, 여기서 PII 마스킹 후 `QueryLog`를 저장한다:

```java
// OllamaService.generate(...) 끝부분
long latency = System.currentTimeMillis() - startTime;

// 거버넌스: 저장 직전 PII 마스킹
PiiRedactionService.Redaction rq = piiRedactionService.redact(userQuestion);
PiiRedactionService.Redaction ra = piiRedactionService.redact(answer);

QueryLog log = new QueryLog();
log.setAugmentedPrompt(prompt);
log.setQuestion(rq.text());
log.setAnswer(ra.text());
log.setModel(model);
log.setLatencyMs(latency);
log.setPiiCount(rq.count() + ra.count());
log.setSources(sources);            // RAG만 채워짐, 그 외 null
queryLogRepository.save(log);
```

> **Failure isolation (gap)**: 현재 저장 실패가 사용자 요청을 함께 실패시킨다. 별도 try/catch로 격리하는 건 아직 미구현(8절 참조).

---

## 5. Read Path — API

| Endpoint                     | Description                                    |
|------------------------------|------------------------------------------------|
| `GET /api/governance/logs`   | All logs (`findAll`, no filters)               |
| `GET /api/governance/stats`  | Aggregates: per-model, per-source-type, KPIs (12절) |
| `POST /api/governance/feedback` | Set up/down feedback on a log (`{id, value}`) |

> 현재 `/logs`는 필터 없이 전체를 반환한다. endpoint/model/{id} 필터는 미구현.

(See `docs/API.md` for request/response examples.)

---

## 6. H2 Console (dev only) — 실제 스키마 기준

H2 console은 dev 프로필에서 `/h2-console`에 노출된다. 접속 정보는 `application-dev.yaml`의 datasource 설정과 일치해야 한다(아래는 기본값 예시).

| Setting  | Value                    |
|----------|--------------------------|
| JDBC URL | `jdbc:h2:mem:miniwatson` |
| User     | `sa`                     |
| Password | (empty)                  |

> 주의: in-memory(dev)라 앱을 재시작하면 `query_log`가 초기화된다. console로 보려면 앱이 떠 있는 상태에서, 질의를 몇 번 한 뒤 조회한다.

### 실제 테이블: `query_log`

코드의 `QueryLog` 엔티티가 만드는 실제 컬럼이다(이전 문서의 `audit_log`는 설계 초안이었고, 실구현과 다르다).

| 컬럼 | 타입 | 의미 |
|---|---|---|
| id | bigint PK | 자동 증가 |
| question | TEXT | 사용자 질문(PII 마스킹됨) |
| answer | TEXT | 답변(PII 마스킹됨) |
| augmented_prompt | CLOB | LLM에 실제 들어간 증강 프롬프트 |
| model | varchar | 사용된 모델명 |
| latency_ms | bigint | 응답 지연(ms) |
| pii_count | int | 이 질의에서 마스킹된 PII 건수 |
| sources | TEXT | 답변 근거 청크(`#id title; ...`, RAG만) |
| created_at | timestamp | 생성 시각 |

> provenance 컬럼명은 엔티티 필드와 같다. 필드가 `sources`면 컬럼도 `sources`, `source`면 `source`. 아래 쿼리는 `sources` 기준이니 본인 스키마에 맞춰 바꾼다.

### 거버넌스 분석 쿼리 (H2 console에서 바로 실행)

```sql
-- 1) 최근 활동
SELECT id, model, latency_ms, pii_count, created_at
FROM query_log
ORDER BY created_at DESC
LIMIT 50;

-- 2) 모델별 사용량 + 평균/최대 지연
SELECT model,
       COUNT(*)        AS calls,
       AVG(latency_ms) AS avg_ms,
       MAX(latency_ms) AS max_ms
FROM query_log
GROUP BY model
ORDER BY calls DESC;

-- 3) 가장 느린 10건
SELECT id, model, latency_ms, question
FROM query_log
ORDER BY latency_ms DESC
LIMIT 10;

-- 4) PII가 탐지된 질의 (거버넌스 인시던트)
SELECT id, pii_count, model, created_at, question
FROM query_log
WHERE pii_count > 0
ORDER BY created_at DESC;

-- 5) PII 탐지 추이 (일자별)
SELECT CAST(created_at AS DATE) AS day,
       COUNT(*)                 AS calls,
       SUM(pii_count)           AS pii_hits
FROM query_log
GROUP BY CAST(created_at AS DATE)
ORDER BY day DESC;

-- 6) provenance 역추적 — 특정 청크(#5)가 근거로 쓰인 답변
SELECT id, model, created_at, question
FROM query_log
WHERE sources LIKE '%#5 %'
ORDER BY created_at DESC;

-- 7) 근거 없이 답한 질의 (RAG 아님 또는 검색 실패) — 거버넌스 점검
SELECT id, model, question
FROM query_log
WHERE sources IS NULL OR sources = ''
ORDER BY created_at DESC;
```

---

## 7. Mapping to watsonx.governance

| MiniWatson concept     | watsonx.governance concept             |
|------------------------|----------------------------------------|
| `QueryLog` row         | Model card · decision record           |
| `model` field          | AI Factsheet model identifier          |
| `sources`              | Provenance / lineage                   |
| `latencyMs`            | Performance monitoring                 |
| `piiCount`             | Sensitive-data exposure signal         |
| `feedback`             | Human evaluation / quality signal      |
| `/stats` aggregates    | Risk dashboard query interface         |

---

## 8. What's NOT in Scope (yet)

| Topic                       | Note                                                                |
|-----------------------------|---------------------------------------------------------------------|
| ~~PII redaction~~ DONE    | Implemented — see section 10 (regex masking before audit-log persist)      |
| Failure isolation           | Audit save failure currently fails the request — no try/catch wrap yet |
| Cryptographic chaining      | Each entry standalone — no hash chain for tamper-evidence           |
| Right-to-be-forgotten        | No DELETE API — subject-scoped purge to be added                    |
| Cost attribution            | `latencyMs` only — no `tokens_in/out` (Ollama doesn't expose easily) |
| Streaming events            | Synchronous write — Kafka/event-bus version is production direction |
| Per-tenant separation       | Single tenant assumed                                               |

These are documented gaps, not silent omissions. Production version would add each item.

---

## 9. Why This Matters for IBM

1. **watsonx parity** — three pillars (data · ai · governance) all visible.
2. **Regulatory awareness** — schema designed against EU AI Act / ISO 42001.
3. **Anti-corruption** — Wikipedia DTO never reaches the audit log directly.
4. **Local-first sovereignty** — no audit data leaves the laptop.
5. **Transparent gaps** — section 8 honestly lists what production would add.

---

## 10. PII 마스킹 (민감정보 자동 가림) — 구현됨

감사 로그는 질문·프롬프트·답변을 그대로 쌓는다. 거기에 이메일·전화번호·주민번호·
카드번호 같은 **개인식별정보(PII)** 가 섞이면, 로그 자체가 유출 위험이 된다.
그래서 **로그에 저장되기 직전** 단계에서 PII를 마스킹한다. 이것이
watsonx.governance가 강조하는 "민감정보 보호 + 감사 가능성"의 소규모 구현이다.

### 동작 흐름

```
LLM 호출
  → 응답 받음
  → PiiRedactionService.redact(question) / redact(answer)   ← 마스킹 + 건수 카운트
  → QueryLog 저장: 마스킹된 텍스트 + piiCount
  → 사용자에겐 원본 답변 반환                                 ← 기능은 그대로
```

핵심 원칙: **사용자 응답은 원본, 저장되는 감사 로그만 마스킹.** 기능을 해치지 않고
기록만 보호한다.

### 탐지 패턴 (정규식 기반)

| 라벨 | 대상 | 예시 → 결과 |
|---|---|---|
| `[EMAIL]` | 이메일 | `john@acme.com` → `[EMAIL]` |
| `[PHONE]` | 전화번호 | `010-1234-5678` → `[PHONE]` |
| `[SSN]` | 주민/사회보장번호 패턴 | `123-45-6789` → `[SSN]` |
| `[CARD]` | 13~16자리 카드번호 | `4111 1111 1111 1111` → `[CARD]` |

`QueryLog`에는 `piiCount`(이 질의에서 마스킹된 건수)가 함께 저장돼, 대시보드
Audit Trail에서 " N"으로 표시된다 → 거버넌스가 민감정보를 실제로 잡아냈음을 가시화.

### 예시

요청:
```json
{ "question": "My email is john@acme.com, what is RAG?" }
```
저장된 로그:
```
question: "My email is [EMAIL], what is RAG?"
piiCount: 1
```

### 한계 (정직하게)

- **정규식 기반**이라 정형 PII만 잡는다. 이름·주소 같은 **비정형 PII**는 못 잡는다
  → 프로덕션은 NER(개체명 인식) 모델이나 Presidio 같은 전용 엔진을 결합해야 한다.
- 카드/전화 패턴은 **오탐(false positive)** 가능 (예: 13자리 일반 숫자열).
- 마스킹은 **로그 한정**이다. 지식베이스 원문(Article)은 검색 품질 때문에 원본 유지 —
  필요하면 ingest 단계에도 같은 서비스를 적용할 수 있다.

이 한계들은 숨긴 게 아니라 **의도적으로 문서화한 gap**이다. 프로덕션 버전은 각 항목을
보강한다.

---

## 11. Provenance (답변 근거 추적) — 구현됨

"이 답이 어디서 나왔나"를 감사할 수 있어야 한다. RAG는 검색된 청크를 근거로 답하므로,
**답변마다 실제로 쓰인 근거 청크**를 audit 로그에 함께 남긴다. 이것이
watsonx.governance의 provenance / lineage에 해당하는 소규모 구현이다.

### 무엇을 기록하나

`QueryLog`에 `sources` 컬럼을 두고, 그 질의에서 LLM에 들어간 **최종 근거 청크들**을
`#id title` 형태로 묶어 저장한다.

```
sources: "#2 Retrieval-augmented generation; #1 Vector database"
```

핵심: **rerank를 거친 뒤의 최종 top-K**를 기록한다. 1차 검색이 아니라 실제 LLM에 전달된
근거라, "왜 이 답이 나왔나"를 정확히 추적할 수 있다. reranker가 후보 순위를 바꿨다면
(예: #3 대신 #2 선택) 그 결과가 그대로 남는다.

### 동작 흐름

```
RagService.ask
  → 1차 검색(top-N) → rerank → 최종 topArticles
  → sources 문자열 생성: topArticles를 "#id title"로 join
  → ollamaService.ask(prompt, model, question, sources)   ← sources 전달
OllamaService.generate
  → QueryLog.setSources(sources)  (save 전에)
  → queryLogRepository.save(log)
```

설계 포인트:

- **RAG 답변만 sources가 채워진다.** summarize·multimodal 같은 비-RAG LLM 호출은
  근거 청크가 없으므로 sources는 null이다(의미상 맞음). 공개 메서드들은 각자 적절한
  값을 내부 `generate(...)`에 넘기고, sources 파라미터는 generate 한 곳에만 둔다.
- **set은 반드시 save 앞에.** `save(log)`는 그 시점 상태를 DB에 쓰므로, set을 뒤에 두면
  메모리만 바뀌고 DB엔 안 들어간다(실제로 이 순서 실수로 sources가 계속 비었었다).
- UI Audit Trail에 Sources 컬럼을 추가해 화면에서 근거를 바로 볼 수 있다.

### 거버넌스 가치

답변과 근거의 연결이 감사 가능해진다. hallucination이 의심되면 "근거 청크에 실제로 그
내용이 있나"를 대조할 수 있고, 잘못된 답의 원인이 검색(엉뚱한 청크)인지 생성(근거는
맞는데 LLM이 왜곡)인지 구분할 수 있다.

### 한계 (정직하게)

- 근거는 **제목+id 문자열**로만 저장한다. 청크 본문 스냅샷은 저장하지 않으므로, 이후
  해당 청크가 수정·삭제되면 당시 근거 원문은 복원 불가 → 프로덕션은 청크 본문 해시나
  버전을 함께 남겨야 한다.
- 근거별 기여도(어느 청크가 답에 얼마나 쓰였는지)는 기록하지 않는다.

---

## 12. 거버넌스 통계 대시보드 — 구현됨

H2 console에서 수동으로 돌리던 집계(6절)를 API 하나로 묶고, 화면에 카드로 띄운다. 감사 로그(`query_log`)와 문서 카탈로그(`document_catalog`)를 한 번에 집계해 거버넌스 현황을 한눈에 보여준다.

### 엔드포인트

```
GET /api/governance/stats
```

반환(JSON):

```json
{
  "totalCalls": 12,
  "avgLatencyMs": 5300,
  "totalPii": 2,
  "totalDocs": 5,
  "byModel": [
    {"model": "ibm/granite4:latest", "calls": 8, "avgMs": 4900},
    {"model": "gemma4", "calls": 4, "avgMs": 14000}
  ],
  "bySourceType": [
    {"sourceType": "file", "docs": 1, "chunks": 101},
    {"sourceType": "image", "docs": 1, "chunks": 1},
    {"sourceType": "wikipedia", "docs": 3, "chunks": 3}
  ]
}
```

### 집계 방식

JPA `@Query`로 DB에서 직접 집계한다(애플리케이션 메모리로 끌어와 세지 않는다).

- `query_log` — 총 호출수(count), 평균 지연(AVG), PII 합계(SUM), 모델별 GROUP BY
- `document_catalog` — 소스 타입별 문서/청크 GROUP BY, 총 문서수(count)

GROUP BY 결과는 Spring Data가 `List<Object[]>`로 주므로, 컨트롤러에서 `{키, 값...}` Map으로 변환한다. 0건 구간은 `COALESCE`로 null 대신 0을 반환해 NPE를 막는다.

### 카드 (UI)

- Total Calls / Avg Latency / PII Hits / Documents (KPI)
- By Model — 모델별 호출수·평균 지연 (모델 전환 효과가 수치로 보임: granite4가 gemma4보다 빠름)
- By Source Type — wikipedia / image / file 별 문서·청크 수

### 의의

거버넌스가 "로그를 쌓는다"에서 "현황을 보여준다"로 올라간다. 모델별 비용(지연), PII 노출 빈도, 지식베이스 구성을 운영자가 즉시 파악할 수 있다. watsonx.governance의 리스크 대시보드에 해당하는 소규모 구현이다.
