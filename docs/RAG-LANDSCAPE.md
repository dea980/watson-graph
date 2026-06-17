# RAG 지형도와 로컬 구현 (RAG Landscape & Local Feasibility)

RAG는 "벡터 검색 후 생성" 하나가 아니다. 패러다임(Naive/Advanced/Modular)과 개별 기법(HyDE, RAPTOR, CRAG, GraphRAG 등)이 있고, 각각 비용과 거버넌스 영향이 다르다.

이 문서가 정리하는 것:
1. RAG의 종류 (패러다임 + 기법 카탈로그)
2. MiniWatson이 어디에 있는지 (보유 / 부분 / 없음)
3. 로컬 스택(Ollama, Spring Boot, pgvector)에서 무엇이 현실적인지
4. 다음 수순 — 효율 대비 임팩트 순서와 코드상 어디에 붙는지

---

## 1. 패러다임 (Gao et al. survey)

| 단계 | 정의 |
|---|---|
| Naive | index → 단일 벡터검색 → 생성. 끝 |
| Advanced | + pre-retrieval(청킹 최적화, 쿼리 변환) + post-retrieval(리랭킹, 압축) |
| Modular | 모듈 교체, 하이브리드, 라우팅, 반복/적응 검색 |

**MiniWatson 위치: Advanced이고 Modular에 한 발.** 하이브리드(벡터+BM25, RRF), 2단계 리랭킹, 멀티모달, tabular까지 보유. 빠진 건 쿼리 변환과 제어흐름 계열. "Naive"는 스위치로 끌 수 있는 베이스라인 한 구성일 뿐이다.

---

## 2. 기법 카탈로그

표기: ✅ 보유 / ◐ 부분 / ✗ 없음. "LLM 호출"은 질의 1건당 추가 호출 수(로컬 실용성의 핵심 지표).

### 질의측 (pre-retrieval)
| 기법 | 무엇 | 언제 | LLM 호출 | 상태 |
|---|---|---|---|---|
| 쿼리 재작성 | 사용자 질의를 검색용으로 다듬음 | 구어체/모호한 질의 | +1 | ✗ |
| Multi-query / RAG-Fusion | 질의 여러 개 생성 후 결과를 RRF로 합침 | recall 끌어올리기 | +N | ✗ |
| HyDE | 가상 답변을 생성해 그걸 임베딩해 검색 | 질의-문서 어휘 차이 클 때 | +1 | ✗ |
| Step-back | 추상화한 상위 질문으로 먼저 검색 | 추론형 질문 | +1 | ✗ |

### 검색·구조
| 기법 | 무엇 | LLM 호출 | 상태 |
|---|---|---|---|
| Hybrid (벡터+BM25, RRF) | 의미검색 + 정확토큰검색 융합 | 0 | ✅ |
| 2단계 Reranking | top-N 받아 top-K로 재정렬 | 0~N(전략별) | ◐ cross는 폴백 |
| RAPTOR | 청크를 재귀 요약해 트리 구축, 전 층 검색 | 빌드 시 대량 | ✗ |
| GraphRAG | 엔티티+관계 그래프 구축, local/global 질의 | 빌드 시 대량 | ✗ |

### 제어흐름 (control-flow)
| 기법 | 무엇 | LLM 호출 | 상태 |
|---|---|---|---|
| CRAG | 검색 결과를 채점, 부족하면 재검색/대체소스 | +1~ | ✗ |
| Self-RAG | 모델이 reflection 토큰으로 "검색할까/인용할까" 판단 | 가변 | ✗ |
| FLARE | 생성 도중 불확실하면 그때 검색 | 가변 | ✗ |
| Adaptive RAG | 질문 난이도로 경로 분기(무검색/단일/멀티홉) | +1(라우터) | ✗ |
| Agentic RAG | 에이전트가 도구·다단계 검색 오케스트레이션 | 多 | ✗ |

### 데이터 종류
| 기법 | 무엇 | 상태 |
|---|---|---|
| Multimodal RAG | 이미지 Q&A + 비전/OCR 그라운딩 | ✅ |
| Tabular RAG | CSV/XLSX를 text-to-SQL(DuckDB)로 집계 | ✅ |

### 평가
| 항목 | 무엇 | 상태 |
|---|---|---|
| Retrieval recall | 골든셋으로 검색 정확도 측정 | ✅ |
| 답변 품질(RAGAS류) | faithfulness, answer-relevance, context-precision | ✗ |

---

## 3. 상황별 선택 가이드 (페르소나 / 시나리오)

기법은 "좋다/나쁘다"가 아니라 **상황에 맞느냐**의 문제다. 누가, 어떤 코퍼스로, 어떤 질문을 하느냐에 따라 정답이 달라진다.

| 상황 / 페르소나 | 핵심 니즈 | 추천 기법 | MiniWatson |
|---|---|---|---|
| 소규모 깨끗한 코퍼스, 사실형 QA (사내 FAQ/위키) | 단순, 빠름 | 기본 벡터 + 가벼운 rerank. 고급기법 이득 작음 | ✅ 현재 데모가 이 상황 |
| 대규모/노이즈 코퍼스, 희귀토큰 질의 (제품명/코드/ID) | 정확 토큰 매칭 | Hybrid(벡터+BM25), rerank 효과 큼 | ✅ |
| 사용자는 구어체, 문서는 전문용어 (어휘 격차) | 질의-문서 정렬 | 쿼리 재작성 / HyDE | ✗ |
| "전체를 종합" 질문 (핵심 테마? 문서 전반 요약?) | 전역 종합 | RAPTOR(요약 트리) 또는 GraphRAG | ✗ |
| 여러 문서를 잇는 멀티홉 ("A와 C의 관계?") | 관계 추론 | GraphRAG | ✗ |
| 규제/의료/금융 — 환각 최소화, 근거 신뢰 필수 | 정확성, 설명가능성 | CRAG/Self-RAG(근거 채점) + provenance 로깅 + faithfulness 평가 | ◐ provenance만 |
| 질문 난이도 천차만별 (검색 불필요~멀티홉) | 비용/지연 최적화 | Adaptive RAG 라우팅 | ✗ |
| 이미지/스캔/차트 섞인 문서 (송장 등) | 멀티모달 그라운딩 | Vision + OCR | ✅ |
| 집계/계산 질문 ("Q4 합계?") | 정확 연산 | Tabular text-to-SQL (RAG 아님) | ✅ |

읽는 법: **MiniWatson은 "소규모 사실형 + 멀티모달 + 표" 상황엔 이미 충분하다.** 코퍼스가 커지면(→ hybrid/rerank 튜닝), 질문이 종합형/멀티홉으로 가면(→ RAPTOR/GraphRAG), 규제 도메인이 되면(→ CRAG + 답변품질 평가) 그때 해당 기법을 켜는 식으로 확장한다. "전부 다 넣는 것"이 목표가 아니라 상황에 맞춰 켜는 것이 핵심.

---

## 4. 로컬에서 무엇이 가능한가 (Ollama / Spring / pgvector)

핵심 제약: **로컬 LLM은 비용은 $0이지만 CPU면 호출당 느리다.** 그래서 로컬 실용성의 기준은 "질의당 LLM 호출 수"와 "빌드 시 호출 총량"이다. 모든 기법이 외부 API 없이 가능하다는 점(주권형, sovereign)은 그대로 장점.

**로컬에 쉬움 (질의당 +1~수 회):** 쿼리 재작성, HyDE, Step-back, Multi-query/RAG-Fusion, CRAG 채점, Adaptive 라우팅, Reranking. → 지연만 감수하면 바로 됨.

**로컬에 가능하나 빌드가 무거움 (1회성 대량 호출):** RAPTOR(층별 요약), GraphRAG(엔티티 추출). → 빌드는 느리지만 1회성, 질의는 일반 검색 수준. CPU면 코퍼스 작게 시작.

**로컬에 까다로움:** Self-RAG(reflection 토큰을 가진 전용 학습 모델 필요 → 로컬에선 프롬프트로 흉내만 가능), FLARE(토큰 단위 생성 제어 필요 → Ollama 표준 API로는 어려움).

**인프라:** pgvector/Postgres가 이미 있으니 벡터 검색·메타 필터는 OK. 그래프가 필요하면 **Apache AGE 확장**으로 같은 Postgres에서 처리 가능(Neo4j 신규 도입 불필요). RAPTOR 트리 노드는 `level` 필드를 가진 벡터로 기존 저장소에 그대로 적재 가능.

---

## 5. 거버넌스 관점 (이 프로젝트의 정체성과 연결)

- **CRAG/Self-RAG** — 검색 결과를 "채점"하므로, 감사 로그에 "왜 이 근거를 채택/기각했나"를 남길 수 있다. provenance가 한 단계 강해진다.
- **GraphRAG/RAPTOR** — 답의 출처를 그래프 경로나 요약 노드로 제시할 수 있어 설명가능성이 올라간다.
- **쿼리 변환** — 원질의와 변환된 질의를 **둘 다 로깅**해야 추적이 된다. 변환만 기록하면 "사용자가 실제로 뭘 물었나"가 사라진다. 거버넌스 주의점.

---

## 6. 추천 로드맵 (효율 대비 임팩트 + 코드 위치)

각 단계는 기존 전략패턴(인터페이스 + `@ConditionalOnProperty`)에 **새 구현 + 설정 스위치**로 붙는다. 기존 코드를 부수지 않고 A/B 비교가 되는 구조.

1. **답변 품질 평가(RAGAS류) — 먼저.** 개선 전후를 측정할 자가 없으면 나머지 작업은 "좋아졌다"를 증명 못 한다. `eval/`에 faithfulness/answer-relevance 추가. *측정이 기능보다 먼저다.*
2. **크로스인코더 제대로 돌리기.** 현재 `CrossEncoderReranker`가 플랫폼 문제로 폴백 중. Linux/Apple Silicon 실구동 확인 또는 ONNX 경로로 대체.
3. **쿼리 변환(재작성 또는 RAG-Fusion).** 가볍고 효과 큼. `RagService.ask()` 진입부, 임베딩 직전에 삽입. 새 `QueryTransformer` 인터페이스 + 설정 스위치로.
4. **CRAG(자기교정).** 제어흐름 입문. `HybridRetriever` 결과를 LLM이 채점 → 부족하면 재질의. 거버넌스 궁합이 가장 좋음.
5. **RAPTOR.** 전역 종합 질문 대응. 인덱싱 파이프라인에 요약 트리 빌드 단계 추가, 노드에 `level` 필드. `IndexingService`/저장소 확장.
6. **GraphRAG.** 가장 큰 도약. Apache AGE로 Postgres에 그래프. 마지막 순서.

---

## 7. 한 줄 결론

지금 MiniWatson은 **Advanced/Modular RAG**다(Naive 아님). "제대로"의 다음 관문은 두 가지다. (a) **답변 품질을 측정하고**(RAGAS), (b) **질의 변환과 자기교정**을 더하는 것. 순서는 항상 측정 먼저, 그다음 기능.
