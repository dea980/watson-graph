# Decision Guide — 언제 무엇을 쓰나

파이프라인의 교체 가능한 전략(chunking, reranking, hybrid, embedding, vector store, DB)을 "언제 뭘 고르나" 기준으로 한곳에 모은 인덱스. 각 항목의 근거·측정은 해당 doc에 있고, 여기선 결론만 본다.

관통하는 규칙 하나: 후처리(rerank/hybrid)는 1차 검색이 약하거나 코퍼스가 크고 노이즈 많을 때만 이득이다. 1차가 강하면 한계이득이 0이거나 음수다. 아래 선택 대부분이 이 규칙의 적용이다.

---

## 1. Chunking 전략

문서를 검색 단위로 쪼개는 방식. 설정: `chunking.strategy`.

| 상황 | 추천 | 이유 |
|---|---|---|
| 기본/대부분 | recursive | 비용 0(문자열 연산만), 문단/문장 경계 보존, 즉시 |
| 의미 경계가 중요 + 인덱싱 시간 여유 | semantic | 주제 단위로 묶어 경계 품질 최고. 단 문장마다 임베딩 호출 -> 850자 문서에 3.45초, 큰 문서는 분 단위 |
| 속도 극단 우선 / 구조 없는 텍스트 | fixed | 가장 빠름. 단어/문장 중간이 잘려 경계 품질 낮음(baseline) |

실측: 같은 850자 문서에서 fixed 8청크(중복·단어잘림), recursive 6청크(경계보존), semantic 5청크(의미묶음, 3.45s). 기본은 recursive.

추가 고려:
- `chunking.max-size` — 작은 문서를 비교할 땐 250, 실사용은 800~1000.
- 긴 단일주제 문서는 청크가 많아도(예: 101청크) 검색이 잘 되지만, "문서 전체가 답"인 질문은 retrieval로 못 잡는다(아래 5절 참고).
- 청킹 앞단의 포맷 추출(PDF/DOCX/HWP/이미지 등 -> 텍스트)은 [INGESTION-FORMATS.md](INGESTION-FORMATS.md).

---

## 2. Reranking 전략

1차 후보(top-N=20)를 재정렬해 최종 top-K(2)를 고른다. 설정: `rerank.strategy`.

| 상황 | 추천 | 이유 |
|---|---|---|
| 1차 검색이 이미 강함 (작은/깨끗한 코퍼스) | none 또는 mmr | 측정상 recall 100%. 후처리 이득 0 |
| 후보에 거의 같은 청크가 여럿 | mmr | 다양성 패널티로 서로 다른 측면을 뽑음 |
| 어휘 불일치/미묘한 의도 구분 + 약한 1차 | llm | 후보를 LLM에 listwise로 줘 의도까지 재정렬. 단 LLM 호출 1회 추가 |
| 대규모·고정밀 필요 + Linux/GPU | cross | DJL cross-encoder 전용 모델. 최고 정밀도. 인텔 맥에선 네이티브 부재로 폴백 |

실측(20케이스): none/mmr recall 100%, **llm rerank는 85%로 오히려 깎임**(pods·negation 등 정답 청크를 top-K 밖으로 밀어냄). 이 코퍼스의 best는 none/mmr.

교훈: **rerank를 무조건 붙이지 않는다.** 1차가 강하면 llm rerank가 정답을 밀어낼 수 있다. 측정으로 결정한다.

---

## 3. Hybrid (벡터 + BM25)

1차 검색을 벡터(의미)만 vs 벡터+BM25(어휘) RRF 융합. 설정: `retrieval.hybrid.enabled`.

| 상황 | 추천 | 이유 |
|---|---|---|
| 정확 토큰 질의 (ID, 코드, 제품명) | hybrid on | 임베딩은 "INV-2026-0042"를 의미로 못 잡음. BM25가 정확 매칭 |
| 의미 질의 위주 + 작은 코퍼스 | 차이 작음 | top-N이 코퍼스를 거의 덮어 벡터만으로 충분 |
| 크고 노이즈 많은 코퍼스 + 희귀 어휘 | hybrid on | 어휘 신호가 변별력을 줌 |

한계: 질의어가 코퍼스 전체에 흔하면(저 IDF) BM25도 약하다. "5.4x" 같은 흔한 숫자나, 문서 전체가 한 주제인 경우는 hybrid로도 못 잡는다.

---

## 4. Embedding 모델 / 차원

측정 완료 — 4개 모델을 동일 코퍼스(35케이스)로 비교. **기본값 = granite-embedding:278m**(한+영 혼재 코퍼스서 recall 97%, 한국어 11/11). 상세·결과표는 [EMBEDDINGS.md](EMBEDDINGS.md) 7절.

| 상황 | 후보 | 측정 결과 |
|---|---|---|
| 한국어+영어 혼재 (기본) | granite-embedding:278m (768, 다국어) | recall 97%, 한국어 만점. **승자** |
| 영어 중심 균형 | nomic (768) | recall 94%, 한국어 1개 놓침 |
| 속도·대량·엣지 | granite-embedding:30m (384) | recall 89%, 가장 작고 빠름 |
| (비권장) | mxbai (1024) | recall 94% — 최대 차원인데 nomic과 동점. 비용만 큼 |

핵심: **차원이 아니라 다국어 학습이 한국어 recall을 갈랐다.** mxbai(1024)가 granite-278m(768)에 짐. 영어 24케이스는 4종 거의 동점, 변별은 전적으로 한국어에서 발생.

주의: 임베더마다 prefix 규약이 다르다(nomic은 search_query/document 필수, granite는 불필요 — 그래서 현재 기본값 granite-278m에선 prefix가 비어 있다). 공정 비교하려면 모델별 prefix를 맞춰야 한다. 분기는 EmbeddingService.prefixFor() 한 곳.

---

## 5. Vector Store (인메모리 vs pgvector)

벡터 검색 저장소. VectorStore 인터페이스로 추상화. 두 구현체를 `vector.store` 설정으로 스위치(상호 배타 @ConditionalOnProperty) — 호출부 무변경. 상세는 [PGVECTOR.md](PGVECTOR.md).

| 상황 | 추천 | 이유 |
|---|---|---|
| 개발/소량/차원 실험 | InMemory (VectorIndex, LSH/brute-force) | 차원 무관, 즉시. 단 재시작 시 재인덱싱 |
| 영속성·대규모·운영 | pgvector (PgVectorStore, HNSW) | 디스크 영속, 재시작 후 재인덱싱 불필요, 수십만+ 확장. 단 차원 고정(vector(768)) |

LSH vs brute-force: 코퍼스가 작으면(수백~수천) brute-force가 항상 정확하고 충분히 빠르다. LSH는 수만+에서 속도를 위해 정확도(recall)를 희생하는 ANN.

차원 고정이 이관 경로를 정한다: pgvector는 vector(768)라 768 임베더(granite-278m/nomic)만 적재된다. 그래서 임베딩 4종 비교(384/768/1024)는 인메모리에서 하고, 승자(granite-278m, 768)만 pgvector로 운영 검증한다 — 비교의 유연성은 인메모리, 운영 영속성은 pgvector로 역할 분담.

---

## 6. 저장 티어 / DB

| 데이터 | 저장 | 이유 |
|---|---|---|
| audit log, document catalog | H2(dev/demo) / Postgres(prod) — OLTP | 행 단위 잦은 쓰기·단건 조회. 트랜잭션 |
| Article + embedding (cold) | Parquet — OLAP/lakehouse | 대량 벡터 압축·스캔. 열 지향 |

프로필: dev=H2 in-memory(빠른 개발), demo=H2 file(영속), prod=Postgres+pgvector. 규모가 커지면 audit는 웨어하우스로 ETL(OLTP/OLAP 분리, DATA-MODEL.md 12절).

---

## 7. Chat LLM (생성 모델)

| 상황 | 추천 | 이유 |
|---|---|---|
| 기본/데모 | ibm/granite4 (2.1GB) | 가볍고 빠름(약 4.9s), IBM 내러티브 |
| 품질 더 필요 | gemma4 (9.6GB) | 느림(약 19s)·메모리 큼. 데모엔 과함 |
| 큰 모델(qwen 23GB 등) | 비권장(로컬) | 로컬 추론은 모델 크기가 곧 비용. GPU/서버 전제 |

로컬 추론 교훈: 모델 크기가 곧 운영 비용. 작은 모델이 데모엔 충분하고, 큰 모델은 GPU/vLLM 서빙이 전제다.

---

## 8. 멀티모달 (Vision vs OCR)

이미지에서 정보 추출 시 역할 분리.

| 추출 대상 | 사용 | 이유 |
|---|---|---|
| 정확한 숫자/텍스트 (송장 금액 등) | OCR (Tesseract) | 비전 모델은 숫자를 환각함($650, $5M 등 지어냄) |
| 레이아웃/문서 유형/맥락 | Vision (llava) | "표인가 차트인가, 무엇에 관한가" |

원칙: 둘을 합치되 충돌 시 **OCR을 권위로** 삼는다(프롬프트에 명시). 결합만으론 부족하고 어느 쪽이 authoritative인지 선언해야 한다. (MULTIMODAL.md)

---

## 관통 규칙

- 측정 없이 최적화 없다. chunking/rerank/hybrid/embedding은 정답셋으로 비교해 정한다.
- 후처리는 1차가 약할 때만 가치. 강하면 한계이득 0 또는 음수.
- 비용/품질 트레이드오프를 명시한다. 큰 차원·큰 모델·semantic은 품질↑ 비용↑.
- 전략은 인터페이스로 추상화해 측정으로 갈아끼운다.
