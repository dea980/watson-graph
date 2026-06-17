> ⚠️ 참고용(reference-only). 이 폴더(`reference/graphrag/`)의 코드는 실제 빌드(`src/`)에 통합되어 있지 않습니다.
> 적용하려면 service/·data/ 파일을 `src/main/java/com/miniwatson/` 아래로 옮기고, HybridRetriever·IndexingService·
> RagService·application.yaml에 연결점을 다시 추가하세요(아래 "변경/신규 파일" 참고). 도메인 사전은 증권·자본시장(공시·IR) 특화 버전입니다.

# GraphRAG (로컬 친화) + 금융·공공 도메인화

miniwatson의 벡터 기반 하이브리드 RAG에 **경량 지식그래프(관계)** 와 **금융·공공 도메인 페르소나**를 얹은 변경 기록.

## 왜 이 형태인가 (로컬 제약)

miniwatson은 **Ollama 로컬** 환경이다. 정통 GraphRAG(LLM으로 청크마다 엔티티·관계 triple 추출)는 로컬에서:

- 인덱싱 비용 폭발 — 청크당 LLM 1회 이상 → 문서 수천 개면 수 시간
- 작은 로컬 모델은 일관된 triple JSON을 못 뽑아 그래프가 노이즈투성이

그래서 **LLM 0회**의 경량 경로를 택했다:

- 엔티티 = **도메인 사전 + 규칙(정규식) + 약어 정규화** (`DomainEntityExtractor`)
- 관계 = 같은 문서에 함께 등장하면 잇는 **공출현(co-occurrence)** 엣지
- 인덱싱이 임베딩 수준으로 빠르고 **결정적(deterministic)** → 로컬에서 멀티홉 검색 실현

> 본질적으로 retrieval(IR) 품질 개선이다: 벡터=의미, BM25=어휘, **그래프=관계**. RAG = IR + 생성.

## 아키텍처

```
ingest ─▶ chunk ─▶ ┌─ VectorStore.add     (의미)
                   ├─ KeywordIndex.add    (BM25 어휘)
                   └─ KnowledgeGraph.add  (도메인 엔티티 co-occurrence 그래프)   ← 신규

ask(question)
  └─ QueryRewriter.rewriteForRetrieval   (약어<->정식명 보강, LLM 0회)          ← 신규
       └─ embed + HybridRetriever.search
            ├─ vector  top-N
            ├─ keyword top-N
            └─ graph   top-N  (멀티홉 BFS, 깊이 2, 감쇠 1.0/0.5/0.25)            ← 신규
            └─ RRF 융합 ─▶ Reranker(mmr/llm/cross) ─▶ 프롬프트
                                                          └─ 금융·공공 페르소나   ← 신규
                                                          └─ Ollama 생성
```

## 변경/신규 파일

신규
- `service/DomainGlossary.java` — 금융·공공 약어/기관/법령/상품 seed 사전
- `service/DomainEntityExtractor.java` — 사전+규칙 엔티티 추출 (LLM 0회)
- `service/QueryRewriter.java` — 도메인 인지 질의 재작성
- `data/KnowledgeGraph.java` — ns별 인메모리 co-occurrence 그래프 + 멀티홉 검색
- `test/.../data/KnowledgeGraphTest.java`, `test/.../service/DomainEntityExtractorTest.java`

변경
- `service/HybridRetriever.java` — RRF에 그래프 후보 합류 (3번째 소스)
- `service/IndexingService.java` — `index/reindex`에서 그래프도 갱신
- `service/RagService.java` — 질의 재작성 적용 + 금융·공공 답변 페르소나
- `resources/application.yaml` — `retrieval.graph.enabled`, `persona.domain.enabled`

## 설정 (A/B 토글)

```yaml
retrieval:
  graph:
    enabled: true        # 그래프 후보 RRF 합류 on/off
persona:
  domain:
    enabled: true        # 금융·공공 답변 페르소나 on/off
```

기존 `retrieval.hybrid.enabled`, `rerank.strategy`와 조합해 벡터-only ↔ 하이브리드 ↔ +그래프를 비교 평가할 수 있다.

## 검증

- **알고리즘**: 멀티홉 시나리오(금융위원회 → 자본시장법 → ETF 문서) 검증 — 2-hop 문서 포착, 무관 문서 제외 PASS.
- **단위테스트**: `KnowledgeGraphTest`(멀티홉/네임스페이스 격리/무관질의), `DomainEntityExtractorTest`(추출/정규화/재작성).
- 로컬 실행:
  ```bash
  ./mvnw -Dtest=KnowledgeGraphTest,DomainEntityExtractorTest test
  ./mvnw test   # 전체 회귀
  ```
  (개발 샌드박스는 JRE만 있어 컴파일 불가 → Spring 연결부는 로컬 빌드에서 확인)

## 한계 / 다음 단계

- 공출현 그래프는 정통 KG보다 관계 정밀도가 낮다(관계 "종류"를 모르고 "연결"만 안다). 정밀 관계가 필요하면 신규 문서에 한해 LLM triple 추출을 **배치/캐시**로 점진 도입.
- 엔티티 사전은 seed 수준 → 운영 코퍼스에 맞춰 `DomainGlossary` 확장 권장.
- 그래프는 현재 인메모리(`@PostConstruct` hydrate). 영속·대용량이 필요하면 `VectorStore`처럼 인터페이스화 후 Neo4j 구현 추가 가능.
- 엣지 가중(공출현 강도) 정규화는 `KnowledgeGraph`에 확장 지점만 두었고 점수에는 홉 감쇠만 반영 — 필요 시 가중 BFS로 강화.
