# 증권 멀티홉 평가셋 (reference-only)

> "측정 없이 최적화 없다." — 그래프(GraphRAG)를 **만들기 전에** 그것이 메울 격차부터 수치화하는 평가셋.
> 기존 `eval/`(Wikipedia·IBM 코퍼스)을 건드리지 않도록 여기 `reference/graphrag/eval/`에 분리해 둠.

## 무엇을 측정하나

핵심 가설: **벡터/BM25로는 안 풀리고 그래프로만 풀리는 멀티홉 질문이 존재한다.**

그래서 케이스를 세 종류로 설계:

- **multihop** — 질문은 사전 엔티티(예: 코스피)에 앵커. 정답 문서엔 그 앵커 단어가 없고, 질문엔 정답 문서의 변별 키워드(예: 자본시장법·파생결합증권)가 없다. 즉 **표면어로는 직접 매칭이 안 되고, 엔티티 관계를 따라가야만** 정답에 도달한다.
- **single-hop** — 대조군. 벡터/BM25가 직접 풀어야 정상.
- **refusal** — 코퍼스에 근거가 없는 질문. recall로는 평가 불가(`--judge`/수동).

단일홉 케이스 중 일부는 질문에 도메인 엔티티가 없어 그래프 시드가 잡히지 않는다(예: `sh-disclosure-where`). 이는 정상 — 단일홉은 벡터(의미)가 풀어야 하고, 그래프는 멀티홉을 담당한다.

## 읽는 법

`run_multihop_eval.py`는 recall을 **single-hop / multihop으로 분해**해 출력한다.

```
config                            overall   single-hop     multihop   multihop-miss
rerank=none,hyb=False           ...          높음           낮음        mh-...
rerank=none,hyb=True            ...          높음           낮음        mh-...
```

- single-hop은 높은데 **multihop이 낮으면 → 그 격차가 그래프가 메울 몫**(=baseline gap).
- 그래프를 통합한 뒤 multihop 열이 오르고 single-hop이 안 떨어지면 → **채택**.

## 실행

```bash
# 0) miniwatson 앱 실행 (별도 터미널)
./mvnw spring-boot:run

# 1) 픽스처 코퍼스 적재
bash reference/graphrag/eval/ingest_multihop.sh

# 2) 평가
python3 reference/graphrag/eval/run_multihop_eval.py
```

## 그래프 통합 후 A/B 하는 법

1. `reference/graphrag/`의 `service/`·`data/` 코드를 `src/main/java/com/miniwatson/` 아래로 이동하고, `HybridRetriever`/`IndexingService`/`RagService`/`application.yaml`에 연결점 추가(상세는 `../GRAPHRAG_PLAN.md`).
2. `AskRequest`에 EVAL-ONLY 토글 `private Boolean graph;` 추가, `RagController`→`RagService`→`HybridRetriever`로 전달.
3. `run_multihop_eval.py`의 `CONFIGS`에 한 줄 추가:
   ```python
   {"rerank": "mmr", "hybrid": True, "graph": True},
   ```
   그러면 같은 표에서 **+그래프 열**이 함께 찍힌다. (통합 전에는 `graph` 파라미터를 보내지 말 것 — 서버가 unknown 필드로 400을 낼 수 있음.)

## 한계 (정직하게)

- 경량 그래프는 **사전 엔티티에만 시드**한다. 그래서 질문이 기업명 같은 비사전 토큰에만 앵커하면 그래프가 못 잡는다 → 멀티홉 질문을 일부러 사전 엔티티(코스피·주가연계증권·상장지수펀드)에 앵커하도록 설계했다. 실제 운영에선 `DomainGlossary` 엔티티 확장 또는 회사명 NER이 필요.
- 픽스처 코퍼스는 관계 구조를 보이기 위한 소형 합성셋이다. 진짜 검증은 **DART 공시 실데이터**로 같은 평가셋을 다시 돌리는 것(다음 단계).
- 토크나이저에 따라 일부 멀티홉을 BM25가 우연히 맞힐 수 있다 — 그건 "이 케이스엔 그래프가 굳이 필요 없다"는 신호이지 평가셋 오류가 아니다.
