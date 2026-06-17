# CHANGELOG

watson-graph(자본시장·공시 GraphRAG)의 변경 기록. 아직 git 베이스라인이 없어 이 문서가 변경의 1차 기록이다. 이후 커밋부터는 git 이력과 병행한다.

형식: Added(신규) / Changed(변경) / Fixed(수정) / Verification(검증) / Notes(판단).

---

## 2026-06-17 — 타입드 관계 그래프(T1-A) + 변별 eval + 프론트 스캐폴딩

세션 목표: co-occurrence 그래프를 **타입드 관계 그래프**로 올리고, 그 효과를 **정직하게 증명할 수 있는 eval**을 갖추고, 프론트(Next) 골격을 세운다. 순서 원칙은 기능과 데이터 우선, UI는 나중.

### Added

- **관계 타입 + 시드 관계표** — `service/DomainGlossary.java`
  - `EdgeType` 9종: SUPERVISES, GOVERNED_BY, ISSUES, LISTED_ON, CALCULATES, REQUIRES, CLASSIFIES, DISCLOSES, UNTYPED(폴백).
  - `Relation` record + `RELATIONS` 시드표 18개(예: `한국거래소 CALCULATES 코스피200`, `자본시장법 CLASSIFIES 파생결합증권`).
  - `relationType(a,b)` — 두 엔티티 사이 시드 관계 조회. LLM 0회(결정적) 유지.
- **경로 반환 검색** — `data/KnowledgeGraph.java`
  - `searchWithPaths()` + `GraphHit`/`PathStep` record. 멀티홉 도달 경로를 `from -(EdgeType)-> to`로 반환. 답변 근거·그래프 시각화(U2)의 데이터원.
- **문서 단위 공출현 엣지** — `KnowledgeGraph.linkDocument()`, `IndexingService.linkDocument()`, `IngestionService.ingestText()`에서 청크 루프 후 1회 호출.
  - 긴 공시가 여러 청크로 갈려도 bridge 엔티티가 청크 경계에서 끊기지 않게 문서 전체 기준으로 엣지를 보강.
- **그래프 관측 API** — `controller/DataController.java`: `GET /api/data/graph/stats?namespace=`.
  - `KnowledgeGraph.stats(namespace)` 추가 — 노드/엣지 수 + **엣지 타입별 분포**. ingest가 그래프까지 됐는지, 타입드 엣지가 생성됐는지 검증.
- **그래프 RRF 가중 토글** — `application.yaml`의 `retrieval.graph.weight: 2.0`.
- **변별 eval 픽스처** — `eval/`
  - 코퍼스 7개 추가(`07_kospi_index` ~ `13_etn`) → 총 15개(본문 13 + 노이즈 2). 하드네거티브(분기·감사보고서, 코스피·코스닥 등) 포함.
  - golden 2개 추가(`mh-product-supervisor` 멀티홉, `sh-quarterly-law` 단일홉) → 총 12 케이스.
  - `eval/FIXTURE-DESIGN.md` — 변별·정직(falsifiable) 설계 근거.
- **테스트 3개** — `KnowledgeGraphTest`: 청크분할 끊김/복구 2개, 시드관계→타입드 경로 1개.
- **프론트엔드(Next.js) 스캐폴딩** — `frontend/` (App Router, TS). 금융 페르소나 일반 RAG 화면, 배포 모델 무관 구조(`next.config` 프록시 + static export 토글). 현재 **파킹**.
- **문서** — `docs/GRAPHRAG-DOMAIN-DESIGN.md`(타입드 설계 정본), `docs/ROADMAP.md`, `docs/PROJECT-STATUS.html`(현황·전략 한 장).

### Changed

- **그래프 자료구조 타입화** — `adjacency`: `Map<String,Map<String,Integer>>` → `Map<String,Map<String,Map<EdgeType,Integer>>>`. 같은 이웃에 복수 타입 보관.
- **검색 점수 모델** — 홉 거리 감쇠(`HOP_DECAY`)에서 **전파 가중**으로 전환: `부모가중 × 홉감쇠 × (엣지가중/maxW) × 타입신뢰도`. 죽어있던 엣지 가중(`maxW`)을 실제 정규화 분모로 사용. 균일·UNTYPED면 기존 거동과 동일(하위호환).
- **RRF 융합** — `HybridRetriever.rrf()`에 weight 인자 추가. 그래프 후보를 `graph.weight` 배수로 가중해, graph-only 멀티홉 정답이 vec/BM25 다중리스트 문서에 밀리던 문제 보정.
- **eval 채점 정밀화** — `eval/run_multihop_eval.py`의 `hit()`: "출처 어디든 키워드 포함"에서 **gold 문서가 실제 검색됐는지**(문서 단위)로. 오답 문서가 같은 단어를 담아도 통과하지 않음 → 하드네거티브가 실제로 작동.
- **golden 보강** — 기존 멀티홉에 `hardNegatives` 명시, bridge를 타입드 표기로, `mh-etf-issuer-index`의 gold를 `01`에서 `09_krx_overview`로 교정.
- **로드맵 재정렬** — 트랙 병행에서 **백엔드 우선 직렬화**(P1 기능+데이터 → P2 UI → P3 배포)로.

### Fixed

- **HybridRetriever 컴파일 오류** — `@Value`를 지역변수에 붙여(Spring 주입 안 됨 → graphWeight=0) 그래프 기여가 0이 되고, `rrf` 인자 수도 안 맞아 **빌드가 깨져 있던 상태**를 정상화. `graphWeight`를 생성자 주입 필드로, `rrf` 시그니처 정리.

### Verification

- **백엔드 E2E 확인** — `/api/rag/ask`가 멀티홉(사업보고서→자본시장법→파생결합증권) 정답을 출처 근거와 함께 반환. Ollama 임베딩 + ingest + RAG 작동. `count=8`(픽스처 확장 전).
- **테스트** — 총 9개(엔진 6 + 추출기 3). 직전 세션에서 8개 그린 확인, 타입드 경로 테스트 추가분 포함 **로컬 빌드 검증 대기**(샌드박스는 JDK 11이라 빌드 불가, 프로젝트는 21 필요).

### Notes (판단)

- **graph의 정당화는 아직 미증명**이다. 직전 eval에서 vector-only가 멀티홉 100%였다(= 이 코퍼스에선 graph 불필요였다는 데이터). 이번 변별 픽스처로 vector가 지는 구간을 만들어 **반증 가능하게** 검증한다.
- 멀티홉에서 +graph가 vector-only를 못 이기면 → "이 도메인엔 graph가 recall로 정당화 안 됨"이 정직한 결론. 그 경우 graph는 기본 off + **설명가능성(타입드 경로) 용도**로 포지셔닝한다.
- 미해결 설계 결정은 `docs/GRAPHRAG-DOMAIN-DESIGN.md` 11장 참고(METRIC 노드화, 법령 조항 세분화, 인명 PII 등 — 현재 보수적 기본값).

### 다음(P1 검증)

```bash
./mvnw -Dtest=KnowledgeGraphTest,DomainEntityExtractorTest test
./mvnw spring-boot:run
bash eval/ingest_multihop.sh
curl 'localhost:8080/api/data/graph/stats?namespace=kr-securities'
python3 eval/run_multihop_eval.py
```
