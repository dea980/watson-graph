# Hybrid Search (벡터 + 어휘 검색)

1차 검색을 벡터(의미)만 쓰던 것에서 벡터 + BM25(어휘)를 합치는 것으로 바꿨다. 두 검색의 약점을 서로 보완해 1차 후보(recall)를 강화한다. 이 문서는 실제로 구현하고 측정한 내용만 기록한다.

## 1. 왜 필요한가

벡터 검색(bi-encoder + cosine)은 의미는 잘 잡지만 정확한 문자열에 약하다.

- 강함: "왜 기업이 AI 가치를 못 얻나" 같은 의미 질의
- 약함: "INV-2026-0042"(송장번호), "granite4"(모델명), 코드/에러코드/고유 ID 같은 정확 토큰. 임베딩은 이런 토큰을 의미로 환원하지 못해 유사한 게 없다고 본다.

BM25(어휘 매칭)는 정반대다. 정확 토큰을 정확히 잡고 의미 일반화는 못 한다. 그래서 둘을 합친다.

```
질의 → [벡터 top-N] ┐
       [BM25 top-N] ┘→ RRF 융합 → 통합 top-N → rerank → top-K → LLM
```

reranking과는 다른 단계다. 하이브리드는 "후보를 어떻게 뽑나"(1차), reranking은 "뽑은 후보를 어떻게 재정렬하나"(2차). 1차가 부실하면 rerank가 살릴 게 없으므로, 하이브리드는 그 1차를 강화한다.

## 2. 구현

### KeywordIndex (BM25, 인메모리 역색인)

VectorIndex의 어휘 짝. namespace별로 분리하고, 시작 시 `@PostConstruct hydrate`로 Parquet에서 재구성한다(벡터 인덱스와 같은 철학).

- 토크나이즈: 소문자 + `[^a-z0-9]+` 분리. "INV-2026-0042" → inv, 2026, 0042
- 역색인: term -> 문서빈도(df), 문서별 토큰/길이
- BM25 점수: IDF(희귀어 가중) x TF 정규화(문서 길이 보정, k1=1.2, b=0.75)

### HybridRetriever (RRF 융합)

벡터·키워드 각각 top-N을 받아 Reciprocal Rank Fusion으로 합친다.

```
score(doc) = Σ 1 / (k + rank_i)     (k = 60)
```

점수 스케일이 다른 두 랭킹(cosine vs BM25)을 순위로만 합치므로 정규화가 필요 없다. 실무 표준. `retrieval.hybrid.enabled=false`면 벡터-only로 폴백(비교용 토글).

### IndexingService (인덱싱 책임 분리)

인덱스가 둘(vector + keyword)로 늘면서, ingest/delete가 각 인덱스를 직접 부르면 결합도가 커진다. 그래서 인덱스 갱신을 IndexingService 한 곳으로 모았다.

```java
public void index(Article a)  { vectorIndex.add(a); keywordIndex.add(a); }
public void reindex(List<Article> all) { vectorIndex.rebuild(all); keywordIndex.rebuild(all); }
```

IngestionService·DataController는 `indexingService.index/reindex`만 부르고 인덱스 종류는 모른다. 새 인덱스를 추가해도 호출부는 안 바뀐다. (실제로 KeywordIndex 추가 시 IndexingService 한 곳만 고쳤다.)

설정:

```yaml
retrieval:
  hybrid:
    enabled: true     # false면 벡터-only
```

## 3. 실측

### 동작 검증 — 정확 문자열 (하이브리드가 빛나는 케이스)

질의: "INV-2026-0042" (default namespace)

하이브리드 ON에서 invoice.png를 1등으로 잡고 정답("INV-2026-0042 ... total $852.50")을 냈다. 송장번호는 임베딩이 의미로 못 잡지만 BM25가 정확 토큰으로 집어냈다.

### 한계 — 소규모/고품질 코퍼스에선 차이가 작다

- default namespace(문서 4개)에선 hybrid ON/OFF 둘 다 invoice를 잡았다. FETCH_N=20이 코퍼스 전체를 거의 덮어, 벡터-only로도 정답이 후보에 들어왔기 때문이다.
- 대규모 namespace(PDF 101청크)에서 "5.4x" 같은 숫자 질의는 ON/OFF 둘 다 못 찾았다. 토크나이저가 "5.4x"를 5, 4x로 쪼개는데 숫자는 흔해서(IDF 낮음) BM25 변별력도 약하다. 즉 모든 정확 토큰이 BM25에 유리한 것은 아니다 — 희귀할수록 강하다.
- "interconnected enterprise" 같은 의미 질의는 벡터-only로도 잘 됐다(하이브리드 우위 아님).

### 통찰

하이브리드의 이득은 1차 검색이 약하거나 코퍼스가 클 때 커진다. 검색이 이미 강하면(좋은 임베더 + 잘 쪼갠 청크 + 작은 코퍼스) 정답이 이미 후보에 있어 추가 이득이 작다 — reranking·MMR에서 본 것과 같은 패턴이다. 효과가 분명한 영역은 크고 노이즈 많은 코퍼스 + 희귀 어휘 질의(코드, 고유 ID, 제품명)다.

## 4. 선택 가이드

벡터-only로 갈지 hybrid로 갈지는 코퍼스 크기와 질의 유형으로 갈린다(3절).

| 상황 | 선택 | 근거 |
|---|---|---|
| 정확 토큰 질의 (ID, 코드, 제품명) + 희귀 | hybrid on | BM25가 정확 매칭. 벡터는 이런 토큰을 못 잡음 |
| 의미 질의 위주 + 작은 코퍼스 | 차이 거의 없음 | FETCH_N=20이 코퍼스를 거의 덮음. on으로 둬도 손해 없음 |
| 크고 노이즈 많은 코퍼스 + 희귀 어휘 | hybrid on | 어휘 신호가 변별력을 줌 |

주의: 정확 토큰이라고 다 잡히는 건 아니다. "5.4x"처럼 흔한 토큰은 IDF가 낮아 BM25도 약하다(3절). 비용이 거의 없으니 기본 on으로 두고, 효과는 코퍼스가 커질 때 본다. 교차 비교는 [DECISIONS.md](DECISIONS.md).

## 5. 다음 단계

- 토크나이저 개선: "5.4x", "INV-2026-0042" 같은 복합 토큰을 통째로도 색인(n-gram 또는 원형 보존)해 희귀 토큰 신호를 살린다.
- RRF 가중: 벡터/BM25에 가중치를 줘 도메인에 맞게 균형 조정.
- 큰 노이즈 코퍼스에서 정량 평가(정답셋 기반 recall@k 비교).
