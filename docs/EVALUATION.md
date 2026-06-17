# Evaluation Harness (검색 품질 측정)

chunking / reranking / hybrid를 "느낌"이 아니라 수치로 비교하기 위한 평가 도구. 작은 정답셋(golden set)에 대해 retrieval recall을 측정한다. "측정 없이 최적화 없다."

## 1. 구성

```
eval/
├── golden.json     # 질문 + 기대 결과 (정답셋)
└── run_eval.py     # /api/rag/ask 호출 → sources 검사 → recall 출력
```

앱 코드가 아니라 외부 보조 스크립트다. 표준 라이브러리만 쓰므로 `python3`만 있으면 된다(설치 불필요). IDE가 모듈을 못 찾는다고 표시해도 터미널 실행은 정상이다.

## 2. 정답셋 (golden.json)

각 케이스는 질문 + namespace + 기대 조건이다. 청크 id를 직접 박지 않고 내용으로 매칭해 유연하게 둔다.

- `expectTitleContains` — 이 문자열을 제목/내용에 포함한 source가 나와야 함
- `expectKeywords` — 나열한 키워드가 **모두**(AND) 검색된 sources에 있어야 함

정답셋은 결과에 맞춰 느슨하게 바꾸지 않는다(끼워맞추기 방지). 정답셋은 고정하고, 설정을 바꿔 어느 조합이 MISS를 줄이는지 본다.

### 케이스를 왜 이렇게 골랐나 (설계 의도)

골든셋은 "쉬운 질문 모음"이 아니라, 검색·생성의 서로 다른 능력과 알려진 약점을 의도적으로 겨냥한 묶음이다. 각 category가 노리는 것:

| category | 노리는 것 (왜 넣었나) | 예시 케이스 |
|---|---|---|
| semantic | 의미 검색의 기본기. 벡터가 강해야 하는 질의 | rag-def, vectordb-def, wiki-def |
| lexical | 정확 토큰(ID/코드). 벡터가 약하고 BM25가 강한 영역 — 하이브리드의 존재 이유 | invoice-number(INV-2026-0042) |
| ocr-fact | OCR로 추출한 정확 숫자/필드. 멀티모달 grounding 검증 | invoice-total($852.50), bill-to(Acme), ocr-line-item($240) |
| vocab-mismatch | 답의 핵심어가 질문에 없음. 어휘 일치에 기대지 않고 의미를 잡는지 | silos, fragmentation-cause |
| discriminative-number | 희귀 토큰(82%, 61%, 5.4x). 변별력 있는 사실 검색. 5.4x는 ubiquitous-term이라 일부러 약점 추적용 | silos-stat, dismantling-boundaries, adoption-multiplier |
| specific-fact | 문서 깊은 곳의 단일 사실 | energy-twin, cross-domain-leadership(79%) |
| definition | 본문 깊숙한 정의를 정확히 끌어오는지 | pods |
| reasoning | 패러프레이즈·부정·다중 사실 종합. 단순 매칭으로 안 되는 것 | rag-vs-finetune, negation-trap, multi-fact |
| refusal | 근거가 없을 때 confident 오답 대신 "모른다"고 하는지(hallucination 억제). 테넌트 격리도 포함 | out-of-scope(프랑스 수도), wrong-namespace |
| korean | 한국어 질의 -> 한국어 문서. 영어중심(nomic) vs 다국어(granite-278m) 임베딩 변별 축 | kr-bcg-code, kr-hackathon-llm, kr-medical-benchmark |

핵심 원칙:
- 일부러 어려운 유형(vocab-mismatch, reasoning, refusal)을 넣어 "쉬운 질문만 통과"하는 착시를 막는다.
- 알려진 약점(5.4x 같은 ubiquitous-term)을 케이스로 박아 회귀를 추적한다.
- refusal 케이스는 retrieval recall로는 평가할 수 없고(정답 청크가 없음) LLM-as-judge로만 의미가 있다 — "근거 없음"을 잘 말하는지가 점수.
- 일부 expectKeywords(79%, 240 등)는 코퍼스에서 본 것 기반이나 100% 확신은 아니다. MISS가 나면 silos 사례처럼 "정답셋 오류 vs 진짜 검색 실패"를 먼저 가린다.

### namespace별 커버리지 (RAG 트랙, 현재 35케이스)

같은 정답셋이 영어 3개 + 한국어 5개 namespace에 걸쳐 있어, 포맷·언어별 검색 강도를 한 번에 본다. (표 데이터 csv/xlsx는 RAG가 아니라 SQL 트랙으로 분리.)

| namespace | 케이스 | 소스(포맷) | 노리는 축 |
|---|---|---|---|
| default | 9 | Wikipedia + 송장(PNG OCR) | 영어 의미·정확토큰·OCR |
| IBM-blueprint-for-agentic-opeation | 11 | IBM blueprint PDF | 영어 희귀숫자·정의·추론 (가장 어려움) |
| IBM-ceo-study-2026 | 4 | IBM CEO study PDF | 영어 숫자 사실 (10/48/76/100%) |
| kr-bcg | 3 | BCG 분석 (HTML) | 한국어 숫자 (30/20/59) |
| kr-hackathon | 2 | 해커톤 (DOCX) | 한국어 정확토큰 (Snowscape, mistral-large2) |
| kr-medical | 2 | 의료AI 발표 (PPTX) | 한국어+영어 (AgentClinic, Bias) |
| kr-hwp | 2 | IBK 채용서류 (HWP) | 한국어 hwplib 추출 (14일, 채용절차) |
| kr-hwpx | 2 | 문체부 채용공고 (HWPX) | 한국어 PrvText 폴백 (문화체육관광부, 3명) |

한국어 11케이스는 임베딩 모델 비교([EMBEDDINGS.md](EMBEDDINGS.md))의 다국어 변별 축이다. nomic(영어 중심)에선 약하게, granite-embedding:278m(다국어)에선 강하게 나오는지로 "한국어 콘텐츠엔 어떤 임베더"를 측정한다. 포맷 다양성(PDF/HTML/DOCX/PPTX/PNG + 한글 HWP/HWPX)은 [INGESTION-FORMATS.md](INGESTION-FORMATS.md)가 어떻게 텍스트로 바뀌는지 다룬다.

### text-to-SQL 트랙 (정형 표, golden_sql.json)

표 데이터의 정밀·집계 질의는 RAG가 아니라 SQL로 채점한다(`run_eval.py --sql`). 각 케이스는 `{table, path, question, expect}` (xlsx는 제목행 skip용 `headerRow` 포함)이고, `/api/tabular/load` 후 `/ask`로 LLM이 만든 SQL을 실행해 `expect` 값이 결과 행에 있는지 본다.

| id | 표(포맷) | 질문 유형 | expect |
|---|---|---|---|
| sql-revenue-q3 | revenue (CSV) | 정확값 조회 | 24.1 |
| sql-revenue-avg | revenue (CSV) | 집계 AVG | 16 |
| sql-revenue-total | revenue (CSV) | 집계 SUM | 83 |
| sql-nasa-hazardous | nasa (CSV, 4687행) | 집계 COUNT | 755 |
| sql-nasa-orbit | nasa (CSV) | 단일값 컬럼 | Earth |
| sql-housing-count | housing (XLSX, 헤더 6행 skip) | 집계 COUNT | 103 |

측정: **6/6 (100%)** — revenue(정확값/AVG/SUM) + nasa(COUNT 755 / 단일값 Earth) + housing.xlsx(COUNT 103, 헤더 6행 skip). `normalize_names`로 공백 컬럼 인용 문제를 제거하고 프롬프트에 샘플행을 줘 리터럴 정확도를 올린 뒤 6/6이 됐다(상세는 [TABULAR-SQL.md](TABULAR-SQL.md) 3절).

집계(AVG/SUM/COUNT)는 벡터 RAG가 원천적으로 못 하는 영역이라 SQL 트랙으로 분리했다(왜·구조는 [TABULAR-SQL.md](TABULAR-SQL.md)). xlsx(kr-housing)도 `registerXlsx`(POI→임시 CSV) 추가로 이제 SQL 트랙에서 채점한다.

순수 로직(청커·BM25·PII·임베딩 prefix·확장자 라우팅) 회귀는 golden.json이 아니라 JUnit 단위 테스트로 잡는다([TESTING.md](TESTING.md)). golden.json은 "검색 품질", golden_sql.json은 "표 SQL 정확성", JUnit은 "구현 정확성"으로 역할이 다르다.

## 3. 실행

```bash
# 현재 설정으로
python3 eval/run_eval.py

# 라벨 붙여서 (조합 비교 기록용)
LABEL="recursive+llm+hybrid" python3 eval/run_eval.py

# API 주소 지정
API=http://localhost:8080 python3 eval/run_eval.py
```

출력: 케이스별 HIT/MISS + 검색된 sources + 전체 recall(%).

## 4. 설정 조합 비교

검색 시점 파라미터(rerank, hybrid)는 재시작 없이 요청별로 오버라이드할 수 있다(아래 5절). chunking은 인덱싱 시점이라 바꾸려면 재인덱싱이 필요하다.

| 축 | 값 | 변경 시점 |
|---|---|---|
| rerank | none / llm / mmr / cross | 요청별 (재시작 0) |
| hybrid | on / off | 요청별 (재시작 0) |
| chunking | fixed / recursive / semantic | 재인덱싱 필요 |

### 측정 결과 (recursive 청킹, 8 cases, 재시작 0으로 1회 실행)

초기 실행은 6/8 (75%)였고 MISS는 silos, interconnected. 두 MISS의 성격을 구분해 보니 하나는 정답셋 문제, 하나는 진짜 검색 문제였다.

- silos: 답변은 정확했다("functional silos block value"). 정답셋이 `["silos","fragmentation"]`를 AND로 요구했는데 두 단어가 다른 청크에 있어 top-2로 못 담았다. 검색 실패가 아니라 정답셋 과엄격. 핵심어 `["silos"]`로 바로잡았다.

### 중요한 함정: 측정 변수가 실제로 적용되는지부터 검증이 필요

처음 조합 비교에서 네 조합이 전부 동일한 결과(75%, 이후 88%)였다. "후처리 이득 0"이라고 결론냈는데 — 사실은 **요청별 rerank/hybrid 오버라이드가 RagService까지 배선되지 않아, 4조합이 전부 같은 기본 설정으로 돌고 있었다.** 같은 걸 4번 측정한 셈.

오버라이드를 끝까지 엮은 뒤(AskRequest -> RagController -> RagService -> HybridRetriever, evalOverrides 게이트) 재측정하니 조합이 실제로 갈렸다. (ill-posed였던 interconnected를 변별력 있는 dismantling-boundaries로 교체한 최종 8케이스 기준:)

| config | recall | misses |
|---|---|---|
| rerank=none, hybrid=false | 8/8 (100%) | - |
| rerank=none, hybrid=true | 8/8 (100%) | - |
| rerank=llm, hybrid=true | 7/8 (88%) | pods |
| rerank=mmr, hybrid=true | 8/8 (100%) | - |

교훈: 비교 결과가 전부 동일하면 "차이가 없다"가 아니라 "변수가 안 먹는 것 아닌가"부터 의심한다. 측정 도구 자체의 검증이 먼저다.

### 발견 1: LLM rerank가 recall을 깎을 수 있다

none/mmr은 8/8(100%)인데 llm만 7/8로 떨어졌다(pods 탈락). LLM 재정렬이 정답이던 pods 청크를 top-2 밖으로 밀어냈다. rerank는 항상 +가 아니다 — 1차 검색이 이미 맞게 뽑은 결과를 후처리가 망칠 수 있다. 이 코퍼스에선 best 조합이 단순 벡터(none) 또는 mmr이고, llm rerank는 역효과였다. "무조건 rerank를 붙이지 말고 측정해서 판단한다"의 근거.

### 발견 2: ubiquitous-term query는 retrieval로 못 잡는다 (interconnected)

interconnected("What is the interconnected enterprise?")는 모든 조합에서 MISS였다. 본문 정확 표현("agentic workflows spanning functional domains")으로 직접 검색해도 정의 청크가 안 떴다.

원인: 이 문서는 101청크 전체가 agentic workflows 주제다. "agentic", "workflows", "functional" 같은 단어가 거의 모든 청크에 있어 BM25 IDF가 0에 가깝고(변별력 없음), 벡터도 모든 청크가 비슷해 정의 청크가 도드라지지 않는다. 즉 질의어가 코퍼스 전체에 깔려 있으면(저 IDF + 낮은 의미 대비) rerank/hybrid/쿼리재작성 무엇으로도 그 청크를 집어낼 수 없다. "문서 전체가 답"인 질문은 retrieval로 답할 종류가 아니다.

이 케이스는 평가용으로 부적절했으므로, 더 변별력 있는 질문(dismantling-boundaries, "61%"라는 고유 수치)으로 교체했다. interconnected를 억지로 통과시키지 않고, 왜 부적절했는지를 기록으로 남긴다.

### 통찰

이 코퍼스에서 1차 검색은 이미 강해서 rerank/hybrid의 한계 이득이 작거나 오히려 음수(llm)였다. 후처리 기법은 1차 검색이 약하거나 코퍼스가 크고 노이즈가 많을 때 가치가 커진다. 그리고 어떤 질의(ubiquitous-term)는 후처리 이전에 retrieval 자체의 한계다.

주의: cross는 인텔 맥에서 네이티브 부재로 폴백되므로(see RERANKING.md) 비교에서 제외했다.

### 확장 측정 (20 cases, recall + LLM-as-judge)

케이스를 8개 카테고리 20개로 늘려 재측정했다.

Retrieval recall:

| config | recall | misses |
|---|---|---|
| rerank=none, hybrid=false | 20/20 (100%) | - |
| rerank=none, hybrid=true | 20/20 (100%) | - |
| rerank=llm, hybrid=true | 17/20 (85%) | pods, negation-trap, multi-fact |
| rerank=mmr, hybrid=true | 20/20 (100%) | - |

Answer quality (LLM-as-judge, granite4, 기본 설정): 평균 4.90 / 5. 20개 중 19개가 5점, invoice-number만 3점.

발견:
- best = none 또는 mmr(recall 100%). llm rerank는 recall을 85%로 깎았다(pods·negation-trap·multi-fact를 top-K 밖으로 밀어냄). 케이스를 늘릴수록 llm rerank의 역효과가 더 선명해졌다 — "무조건 rerank를 붙이지 않는다"의 정량 근거.
- refusal 검증 성공: out-of-scope("프랑스 수도")와 wrong-namespace(타 테넌트의 invoice 질의) 둘 다 judge 5점. 근거가 없을 때 confident 오답을 만들지 않고 "모른다/근거 없음"으로 답했다. hallucination 억제 + 테넌트 격리가 생성 단에서도 확인됐다.
- invoice-number(3점): 질의가 "INV-2026-0042" 토큰뿐이라 무엇을 답할지 모호했고, 답변 품질이 낮게 채점됐다. retrieval은 HIT(invoice 청크를 찾음)지만 generation은 약했던 케이스 — 두 축을 따로 보는 이유.

### 풀세트 측정 (35 cases, 현재 — 한국어 11 + 약어 보정 적용)

코퍼스를 13 namespace로 늘리고(한국어 HWP/HWPX 포함, 5절) 기본 임베더를 비교 승자 granite-embedding:278m으로 둔 현재 상태. 임베딩 모델 4종 비교는 [EMBEDDINGS.md](EMBEDDINGS.md) 7절.

| config | recall | misses |
|---|---|---|
| rerank=none, hybrid=false | 35/35 (100%) | - |
| rerank=none, hybrid=true | 35/35 (100%) | - |
| rerank=llm, hybrid=true | 33/35 (94%) | (실행마다 변동, llm 비결정성) |
| rerank=mmr, hybrid=true | 35/35 (100%) | - |

마지막까지 남던 구조적 miss `ceo-caio-adoption`(질의는 "Chief AI Officer", 정답 청크엔 약어 "CAIO"만)을 **약어 확장**(AcronymExpander, [CHUNKING.md](CHUNKING.md) 2.5)으로 닫아 mmr 34/35 -> 35/35가 됐다. LLM 0회의 결정적 보정이다. llm rerank만 여전히 33/35로 깎이는데(아래 발견), 기본값이 mmr이라 운영엔 영향 없다.

### LLM-as-judge의 한계 (정직하게)

- 자기 채점 편향: 답변과 채점을 같은 계열(로컬 Ollama)로 하면 점수가 후하게 나오는 경향이 있다(평균 4.90이 그 영향일 수 있다). 절대 점수보다 설정 간 상대 비교에 쓰는 게 안전하다.
- 작은 로컬 judge 모델은 노이즈가 있다. 숫자만 강제(num_predict 작게)하고 파싱을 견고하게 했지만, 동점이 많아 변별력이 낮을 수 있다.
- 더 엄밀하려면 더 강한 judge 모델, 다중 judge 합의, 사람 라벨과의 상관 검증이 필요하다.

## 5. EVAL-ONLY: 요청별 오버라이드 (프로덕션 전 제거)

평가를 재시작 없이 빠르게 돌리려고, `/api/rag/ask`가 요청 본문으로 `rerank`/`hybrid`를 받아 그 요청에만 적용하도록 했다.

```json
{ "question": "...", "namespace": "...", "rerank": "llm", "hybrid": false }
```

이 오버라이드는 **평가 전용**이다. 프로덕션에서 외부 요청이 검색 전략을 마음대로 바꾸면 일관성·보안 문제가 된다. 그래서:

- 관련 코드에는 `// EVAL-ONLY` 주석을 달았다. 정리할 때 한 번에 찾는다:
  ```bash
  grep -rn "EVAL-ONLY" src/
  ```
- 프로덕션 전 처리: 오버라이드 필드/분기를 제거하거나, 평가 프로필에서만 허용하도록 잠근다(예: dev/demo 프로필 또는 내부 헤더 게이트).
- 제거해도 기본 동작은 그대로다. 오버라이드가 null이면 설정값(`rerank.strategy`, `retrieval.hybrid.enabled`)을 쓰기 때문이다.

영향 받는 위치(대략):
- `dto/AskRequest` — `rerank`, `hybrid` 필드
- `RagService.ask(...)` — 오버라이드 인자 분기
- `HybridRetriever.search(..., hybridOverride)` — 오버라이드 파라미터
- `RagController` — 요청 필드 전달

## 6. 다음 단계

- 답변 정확도(생성 품질) 측정 추가 — 답에 기대 키워드 포함 여부. 지금은 retrieval recall만.
- 조합 자동 루프 — run_eval.py가 rerank x hybrid 조합을 요청 오버라이드로 한 번에 돌려 표 출력.
- 정답셋 확장 — 케이스 수를 늘려 통계적 신뢰도 확보.
