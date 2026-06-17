# GraphRAG 도메인 재설계 — 타입드 관계 그래프 (자본시장 v2)

> 선행 문서: `reference/graphrag/GRAPHRAG_PLAN.md` (v1, co-occurrence 그래프).
> 이 문서는 v1 위에 **타입드 관계(typed edge)** 를 얹는 v2 설계다. 코드 구현 전 합의용 설계서.

## 1. 배경과 문제

v1 그래프는 노드는 타입을 갖지만(`EntityType{INSTITUTION, LAW, PRODUCT, EVENT, INDEX}`, `DomainGlossary.typeOf`) **엣지에는 타입이 없다.** 같은 문서에 함께 나오면 무방향 공출현 엣지로 잇는다. 두 가지가 깨진다.

- **정밀도** — 아무 동시언급이나 엣지가 된다. 우연히 한 문서에 같이 등장한 무관한 엔티티가 2홉 탐색에 노이즈를 들인다.
- **설명력** — 왜 그 경로로 답에 닿았는지 못 보여준다. CLAUDE.md의 "왜/어디/어떻게를 유저가 알게 하라" 원칙에 어긋난다.

또한 현재 멀티홉 eval은 변별력이 없다. 코퍼스 8개(본문 6 + 노이즈 2), 각 100~142자, 지표가 recall@3이라 vector-only가 이미 multihop 3/3을 찍는다. 그래프가 일할 헤드룸이 없다. 데이터 재설계는 9장에서 다룬다.

## 2. 목표와 비목표

**목표**
- 엣지에 관계 타입을 부여해 멀티홉 경로 자체가 답의 근거가 되게 한다.
- LLM 0회(로컬 결정적) 제약을 유지한다. v1의 핵심 가치다.
- 자본시장(공시, IR) 도메인 안에서 깊이를 우선한다.
- 기존 co-occurrence 경로와 하위호환을 유지한다(타입 미상 엣지로 흡수).

**비목표(후속 레이어)**
- 은행/여신, 보험, 자금세탁방지(AML)로의 도메인 확장. 이 스키마가 검증된 뒤 같은 틀로 얹는다.
- LLM 기반 관계 추출. 로컬 비용/노이즈 문제로 보류한다.

**왜 자본시장 우선인가**
- DART 전자공시가 공개 API라 기업, 공시 실데이터를 결정적으로 적재할 수 있다.
- 기업-공시-법령-상품-기관-지수가 자연스러운 멀티홉이라 관계 밀도가 가장 높다.
- 데이터 수집 비용이 가장 낮아 검증 사이클이 빠르다.

## 3. 노드 스키마

v1의 5타입을 유지하고 멀티홉의 중심 노드인 **기업/발행사**를 추가한다.

| 타입 | 설명 | 시드 출처 | 비고 |
|---|---|---|---|
| INSTITUTION | 감독(FSC/FSS), 시장(KRX/KSD), 협회(KOFIA) | `DomainGlossary.INSTITUTIONS` | 유지 |
| ISSUER | 상장사, 발행사 | DART 고유번호 API | **신규**, 실데이터 노드 |
| LAW | 법령, 시행령, 규정, 조항 | `LAWS` + 패턴 | 조항 단위까지 세분화 검토 |
| PRODUCT | 주식/채권/펀드/ETF/ELS 등 | `PRODUCTS` | 유지 |
| INDEX | 코스피, 코스닥, 코스피200 | `INDICES` | 유지 |
| EVENT | 사업보고서, 공매도, IPO, 증자 등 공시/행위 | `EVENTS` | 유지 |
| METRIC | PER/PBR/ROE 등 지표 | `ACRONYMS` 일부 | 노드화 여부는 결정 필요(11장) |

기업(ISSUER)이 빠져 있던 게 v1의 가장 큰 공백이다. "삼성전자가 발행한 상품", "이 기업의 사업보고서 근거 법령" 같은 질문이 기업 노드 없이는 성립하지 않는다.

## 4. 엣지(관계) 타입 스키마

재설계의 알맹이. 방향성 있는 타입드 관계로 바꾼다.

| 관계 타입 | 방향 | 예시 | 추출 경로 |
|---|---|---|---|
| SUPERVISES (감독) | INSTITUTION → ISSUER/INDEX/PRODUCT | 금융감독원 → 상장사 | 시드표 |
| GOVERNED_BY (규제근거) | PRODUCT/EVENT → LAW | 주가연계증권 → 자본시장법 | 패턴("~에 따라"), 시드표 |
| ISSUES (발행) | ISSUER → PRODUCT | 자산운용사 → 상장지수펀드 | DART 메타, 패턴("~를 발행") |
| LISTED_ON (상장) | PRODUCT/ISSUER → INDEX/MARKET | 코스피200 ETF → 한국거래소 | 시드표, DART |
| CALCULATES (산출) | INSTITUTION → INDEX | 한국거래소 → 코스피200 | 시드표 |
| REQUIRES (의무) | LAW → EVENT | 자본시장법 → 사업보고서 | 시드표, 패턴 |
| CLASSIFIES (분류) | LAW → PRODUCT | 자본시장법 → 파생결합증권 | 시드표 |
| DISCLOSES (공시) | ISSUER → EVENT | 상장사 → 분기보고서 | DART 메타 |
| UNTYPED (공출현) | 무방향 | v1 폴백 | co-occurrence |

UNTYPED는 v1 co-occurrence를 그대로 흡수한다. 시드/패턴/DART가 못 잡은 관계도 약한 엣지로 살려, 재현율을 떨어뜨리지 않으면서 점진 전환을 가능케 한다.

## 5. 관계 추출 전략 (LLM 0회 유지)

타입드 관계를 LLM 없이 뽑는 세 갈래 + 폴백.

1. **시드 관계표** — `DomainGlossary`에 선험 관계를 박는다. 결정적이고 고정밀.
   ```
   (한국거래소, CALCULATES, 코스피200)
   (자본시장법, REQUIRES, 사업보고서)
   (자본시장법, CLASSIFIES, 파생결합증권)
   ```
   인덱싱 때 두 엔티티가 한 문서에 같이 나오고 시드표에 관계가 있으면 그 타입으로 엣지를 단다.

2. **패턴 규칙** — 표면 단서로 타입을 부여한다. v1의 `LAW_PAT`를 확장.
   - `~에 따라`, `~에 근거하여`, `~상의` 뒤 법령 → GOVERNED_BY
   - `~를 발행`, `~를 상장` → ISSUES, LISTED_ON
   - 오탐 방지: 같은 문장(또는 N자 윈도) 안에서만 적용, 타입 충돌 시 시드표 우선.

3. **DART 구조화 메타** — 공시는 (기업, 공시유형, 제출일, 근거법령)이 반정형이다. co-occurrence 추측 없이 ISSUES/DISCLOSES/GOVERNED_BY를 직접 적재한다. 실데이터 진입점.

4. **co-occurrence 폴백(UNTYPED)** — 위에서 타입을 못 정하면 v1처럼 무방향 약한 엣지로 남긴다.

**신뢰도(confidence)**: 시드표/DART = 높음, 패턴 = 중간, co-occurrence = 낮음. 점수와 설명에 반영한다(6장).

## 6. 엔진 변경 영향

**자료구조** — `KnowledgeGraph`의 인접 리스트를 타입드로 확장.
```
// v1
Map<String, Map<String, Integer>> adjacency;            // entity -> neighbor -> count
// v2
Map<String, Map<String, Map<EdgeType, Integer>>> adjacency;  // entity -> neighbor -> (type -> weight)
```
같은 두 엔티티 사이에 복수 타입 관계가 가능하므로 타입별 가중을 분리해 보관한다.

**검색 점수** — 전파 가중에 타입 신뢰도를 곱한다. 이미 들어간 엣지 가중 정규화(`w/maxW`)에 타입 계수를 추가:
```
childW = parentW * HOP_STEP * (w / maxW) * typeConfidence(type)
```
UNTYPED는 낮은 typeConfidence라 타입드 경로가 우선된다. 균일/단일타입이면 v2도 v1 거동을 재현해 하위호환.

**경로 설명(근거 반환)** — BFS가 밟은 (엔티티, 관계타입) 시퀀스를 답과 함께 반환한다.
```
사업보고서 ──REQUIRES── 자본시장법 ──CLASSIFIES── 파생결합증권
```
RagService가 이 경로를 답변 근거(grounding)로 노출 → 거버넌스 감사와 사용자 설명을 동시에 만족.

**하위호환** — 기존 `add()`/`linkDocument()`는 타입 미상이면 UNTYPED로 적재. `KnowledgeGraphTest`의 co-occurrence 케이스는 UNTYPED 경로로 그대로 통과한다. RRF 융합, `graph.weight` 토글은 변경 없음.

## 7. 멀티홉 시나리오

기존 `eval/golden_multihop.json`을 타입드 경로로 다시 본다.

| 질문(앵커) | 타입드 경로 | 정답 |
|---|---|---|
| 공매도가 이뤄지는 시장의 대표 지수 | 공매도 ─(LISTED_ON)→ 한국거래소 ─(CALCULATES)→ 코스피200 | 코스피200 |
| 사업보고서를 의무화한 법률이 파생상품으로 분류하는 증권 | 사업보고서 ─(REQUIRES⁻¹)→ 자본시장법 ─(CLASSIFIES)→ 파생결합증권 | 파생결합증권 |
| 상장지수펀드가 추종하는 지수를 산출하는 기관 | 상장지수펀드 ─(LISTED_ON)→ 코스피200 ─(CALCULATES⁻¹)→ 한국거래소 | 한국거래소 |

타입드면 경로가 곧 근거다. co-occurrence면 "셋이 같이 나옴"에 그쳐 왜 그 답인지 설명이 안 된다.

## 8. 데이터와 거버넌스

- **출처/적재** — DART 공개 API(기업 고유번호, 공시목록, 보고서). 적재 시 출처 URL과 제출일을 메타로 보존해 감사 추적과 신선도 판단에 쓴다.
- **테넌트 격리** — 그래프는 namespace별로 분리(현 구조 유지). DART 실데이터는 `kr-securities` 같은 전용 ns에 적재하고 `TenantAccessChecker`로 적재/조회 권한을 강제한다.
- **PII** — 자본시장 공시 데이터는 기업 단위라 개인정보 노출은 낮지만, 임원/대주주 인명이 포함될 수 있다. 기존 PII 거버넌스 경로를 통과시키고 그래프 노드에는 인명을 노드화하지 않는다(결정 필요, 11장).
- **결정성/재현성** — 추출은 LLM 0회라 같은 입력이면 같은 그래프. eval과 감사가 재현 가능하다.
- **근거 노출** — 6장의 경로 설명을 답변에 붙여 "왜 이 답인지"를 사용자와 감사자가 같이 본다.

## 9. eval 재설계 연결

변별력 있는 픽스처의 조건(현 eval이 천장에 박힌 이유의 역).

1. 코퍼스 30~50개로 확장 — recall@3가 실제로 선택적이 되게.
2. 하드 네거티브 — 날씨/점심이 아니라 같은 주제 오답 문서(코스피 vs 코스닥 vs KRX 개요).
3. vector-hard 멀티홉 — 질문 앵커 A와 정답 C가 표면 어휘를 공유하지 않고, C의 형제 문서가 A의 이웃을 공유해 랭킹 경쟁을 만든다.
4. 긴 문서 + 작은 max-size — 정답 엔티티가 질문과 안 겹치는 청크에 들어가 `linkDocument`가 일하게.
5. recall@1 또는 @2로 조이기.

타입드 그래프는 위 픽스처에서 비로소 vector-only를 이긴다. 코드와 데이터가 함께 가야 숫자가 움직인다.

## 10. 로드맵

- **Phase A — 스키마와 엔진**: `EdgeType` 도입, `adjacency` 타입드 확장, 시드 관계표(`DomainGlossary`), 타입 신뢰도 점수, 경로 설명 반환. 하위호환 테스트 그린 유지.
- **Phase B — 추출기**: 패턴 규칙 확장(`DomainEntityExtractor` 또는 신규 `RelationExtractor`), 시드표 적용. 단위 테스트로 타입 정확도 검증.
- **Phase C — DART 적재**: 공개 API 커넥터, 구조화 메타에서 ISSUES/DISCLOSES/GOVERNED_BY 직접 적재. 기업(ISSUER) 노드 실데이터.
- **Phase D — eval 재설계**: 9장 조건의 변별 픽스처. 타입드 on/off A/B로 효과를 숫자로 증명.

## 11. 결정 필요 / 미해결

- METRIC(PER/PBR 등)을 노드화할지, 약어 정규화로만 둘지.
- LAW를 조항 단위까지 세분화할지(자본시장법 vs 자본시장법 제159조). 정밀도 이득 vs 노드 폭발.
- 임원/대주주 인명을 노드화할지(설명력 vs PII 리스크). 현 제안은 비노드화.
- 같은 청크 내 쌍이 `add()`와 `linkDocument()`에서 이중 카운트되는 v1 잔여 이슈를 v2 적재 일원화로 정리할지.
- 패턴 규칙의 적용 윈도(문장 vs N자)와 타입 충돌 우선순위 확정.
