# Reranking (재정렬)

1차 벡터 검색으로 넉넉히 후보를 뽑은 뒤, 더 정밀한 기준으로 재점수해 최종 top-K만 LLM에 넘기는 단계. 이 문서는 MiniWatson에서 실제로 구현하고 측정한 내용만 기록한다.

## 1. 왜 필요한가

벡터 유사도(cosine)는 "대충 비슷"은 잘 잡지만 "질문 의도에 진짜 맞는지"는 약하다. 정답 청크가 유사도 순위 3등, 5등에 있을 수 있는데 바로 top-2만 자르면 놓친다(recall 손실).

그래서 2단계로 나눈다.

```
질의 -> 1차 검색(top-N, 예: 20) -> rerank(재점수) -> top-K(2~3) -> LLM
         recall 확보(넓게 건짐)      precision 확보(정밀하게 좁힘)
```

- FETCH_N = 20 : 1차 벡터 검색이 뽑는 후보군
- TOP_K = 2 : rerank 후 LLM에 최종 전달

RagService는 vectorIndex.search(ns, q, FETCH_N)로 후보를 뽑고, reranker.rerank(question, candidates, TOP_K)로 좁힌다.

## 2. 구현 (전략 패턴)

Chunker와 동일하게 인터페이스 + 구현체 + 설정 전환 구조.

```
Reranker (interface)
 ├─ NoopReranker          @Component("none")   1차 검색 top-K 그대로 (비교 기준선)
 ├─ LlmReranker           @Component("llm")    LLM에 후보를 주고 관련 순서로 고르게 함
 ├─ MmrReranker           @Component("mmr")    관련도 + 다양성으로 재점수
 └─ CrossEncoderReranker  @Component("cross")  DJL cross-encoder 모델로 (질문, 청크) 쌍 직접 점수화 [인텔 맥에선 네이티브 부재로 폴백, Docker/Linux에서 동작]
```

IngestionService의 Chunker와 같은 방식으로 주입한다.

```yaml
rerank:
  strategy: llm     # none | llm | mmr | cross
```

```java
this.reranker = rerankers.getOrDefault(strategy, rerankers.get("llm"));
```

### 2.1 NoopReranker (기준선)

1차 검색 결과 top-K를 그대로 반환한다. rerank를 끈 상태를 재현해 before/after 비교의 기준으로 쓴다.

### 2.2 LlmReranker (listwise)

후보 전체를 한 프롬프트에 번호와 함께 나열하고, LLM에게 가장 관련된 번호만 순서대로 받는다.

- listwise : LLM 호출 1회로 전체를 한 번에 재정렬 (후보마다 부르지 않음)
- 후보당 300자 제한 : 20개 x 전문이면 프롬프트가 폭발하므로 잘라 넣는다. 관련도 판단엔 앞부분으로 충분
- 견고 파싱 : 응답에서 번호만 정규식으로 추출, 범위 밖/중복 제거, 실패 시 상위 top-K 폴백

### 2.3 MmrReranker (Maximal Marginal Relevance)

관련도만 보던 것을 관련도 + 다양성으로 재점수한다. 1차 top-N에 거의 같은 내용의 청크가 여러 개 끼면 그냥 top-K를 자를 때 중복만 LLM에 가서 컨텍스트가 낭비된다. MMR은 이미 고른 것과 너무 비슷한 후보를 감점해 서로 다른 측면의 청크를 뽑는다.

```
MMR score = lambda * (질의 관련도) - (1 - lambda) * (이미 선택된 것들과의 최대 유사도)
```

- lambda = 0.6 : 관련도를 약간 우선하면서 다양성도 반영
- 질의 관련도는 1차 검색 순위(rank)로 근사한다. 후보가 이미 유사도순으로 오므로 질의 임베딩을 다시 계산하지 않는다. 비용 0
- 청크 간 유사도는 Article에 저장된 768-dim 임베딩끼리 cosine으로 계산

### 2.4 CrossEncoderReranker (DJL, 전용 reranker 모델)

지금까지의 임베딩 검색은 bi-encoder다. 질문과 청크를 각각 따로 벡터화한 뒤 cosine으로 비교한다. 빠르고 미리 인덱싱할 수 있지만, 질문과 청크 사이의 토큰 단위 상호작용 정보가 사라진다.

cross-encoder는 질문과 청크를 한 쌍으로 모델에 같이 넣어 관련도 점수 하나를 직접 출력한다. 토큰끼리 상호 주목하므로 훨씬 정확하다. 대신 미리 인덱싱할 수 없고(질의마다 계산), 후보마다 추론을 1번씩 돌려야 한다. 20개 후보면 추론 20회.

구현:

- DJL(Deep Java Library) + PyTorch 엔진 + HuggingFace tokenizers
- 모델: BAAI/bge-reranker-base (cross-encoder reranker 표준). djl:// URL로 자동 다운로드(첫 실행 시 수백 MB)
- lazy 로드: @PostConstruct 대신 cross가 실제로 호출될 때 ensureLoaded()로 1회만 로드, @PreDestroy에서 해제. 기본 rerank가 mmr이라 cross를 안 쓰면 모델 로드 시도 자체가 없어 startup 로그가 깨끗하다(인텔 맥의 네이티브 부재 에러도 안 뜸).
- 모델 로드 실패 시 model=null로 두고 상위 top-K 폴백. 폴백은 의도된 동작이라 ERROR가 아니라 WARN으로 찍는다. 네이티브 라이브러리/다운로드 환경 문제로 안 떠도 앱이 죽지 않게
- 후보당 512자 제한(입력 토큰 한도), optSigmoid(true)로 점수를 0~1로

비용 순서: none(0) < mmr(계산만) < llm(LLM 호출 1회) < cross(후보마다 추론). 정확도는 일반적으로 그 반대 순.

#### 검증 결과: 인텔 맥에서는 폴백 (graceful degradation)

cross를 켜고 질의했더니 sources가 none과 동일한 #3, #24가 나왔다. 결과만으로는 cross가 동작한 것인지 폴백한 것인지 구분되지 않아 서버 로그를 확인했다.

```
ai.djl.util.Platform : Found matching platform from: tokenizers-0.30.0.jar
LibUtils : library not found in classpath: native/lib/osx-x86_64/cpu/libtokenizers.dylib
CrossEncoderReranker : model load failed, will fall back: Failed to load Huggingface native library.
```

처음엔 IBM Semeru(OpenJ9) JVM 탓을 의심했으나, HotSpot(Temurin)으로 바꿔 다시 돌려도 같은 에러가 났다. 즉 JVM 종류 문제가 아니었다. 진짜 원인은 osx-x86_64(인텔 맥)용 네이티브 라이브러리 부재다. DJL이 의존하는 PyTorch/tokenizers 네이티브가 인텔 맥 빌드를 더는 제공하지 않아(PyTorch가 x86_64 macOS 휠 중단), 이 머신에는 네이티브가 없다. Apple Silicon 맥이나 linux-x86_64에서는 정상 동작한다.

확인된 사실:
- 구현 자체는 정확하다(컴파일, 빈 주입, translator 구성, StringPair 입력 모두 맞음).
- 이 플랫폼(인텔 맥)에서는 네이티브 부재로 실행 불가 -> 폴백.
- 폴백 설계가 의도대로 동작했다. 모델 로드 실패 시 앱이 죽지 않고 상위 top-K를 반환했다(graceful degradation). #3, #24는 그래서 나온 1차 검색 top-2다.

cross-encoder의 실제 동작 검증은 linux-x86_64를 지원하는 Docker 컨테이너에서 수행한다(배포 문서 참고). "맥에서는 폴백, 컨테이너에서는 동작"이 의도된 동작이다.

교훈: 에러 원인을 한 가지(OpenJ9)로 단정하지 않고, HotSpot으로 바꿔 변인을 제거한 뒤 로그의 실제 메시지(native lib 부재)로 원인을 좁혔다. 또한 외부 모델 런타임은 폴백 경로를 반드시 둬서, 네이티브/환경 문제로 핵심 기능이 막혀도 서비스 전체가 죽지 않게 한다.

## 3. 실측 비교 (namespace: IBM 리포트 PDF 101청크)

### 3.1 쉬운 질문 - 차이 없음

질문: "What digital twin capability do energy companies value most?"

| 전략 | sources |
|---|---|
| none | #32, #26 |
| llm | #32, #29 |

둘 다 정답 청크 #32("57% energy organizations ... optimization recommendations")를 1등으로 잡았다. 질문 단어가 정답 청크에 거의 그대로 있어 1차 검색만으로 충분했고, rerank는 2등만 바꿨다. 답도 둘 다 정확("optimization recommendations").

### 3.2 까다로운 질문 - 차이 발생

질문: "Why do companies struggle to get value from their AI investments?" (본문에 "silos"라는 단어 없이 의도로 물음)

| 전략 | sources | 답변 초점 |
|---|---|---|
| none | #3, #24 | "siloed functions block value, 82%" (구조가 문제다, 일반론) |
| llm | #2, #25 | "fragmentation/disconnected workflows가 biggest constraint" (직접 인과) |

rerank가 #3 대신 #2를 1등으로 끌어올렸다. #2는 "Fragmentation - not talent or AI maturity - is the biggest constraint: 82%..."로, "왜 struggle하는가"라는 질문 의도에 더 직접적인 인과를 준다. 벡터 유사도는 #3과 #2를 비슷하게 보지만, 질문 의도까지 고려하면 #2가 낫다는 판단을 LLM이 한 것이다. 1차 검색만으로는 못 하는 일이다.

### 3.3 MMR 다양성 효과

질문: "What are agentic workflows and how do they work?" (본문에 workflow 관련 유사 청크가 많음)

| 전략 | sources | 2등 청크 |
|---|---|---|
| none | #38, #45 | #45 - #38과 같은 obstacle/redesign 맥락 |
| mmr | #38, #31 | #31 - Cross-workflow impact, 다른 측면 |

둘 다 #38을 1등으로 유지하되 2등이 바뀌었다. MMR이 #38과 덜 겹치는 #31을 끌어올리고, #38과 맥락이 비슷한 #45를 밀어냈다. 유사도만 보면 #45가 2등이지만, 다양성 패널티가 걸려 서로 다른 관점의 청크 조합이 됐다.

참고로 3.2의 "struggle" 질문에 mmr을 돌리면 none과 동일한 #3, #24가 나왔다. #3과 #24가 이미 서로 다른 청크라 다양성 패널티가 걸릴 게 없었기 때문이다. MMR은 후보에 중복이 있을 때만 효과가 난다.

## 4. 통찰

- rerank의 이득은 1차 검색이 약할 때 커진다. 검색이 이미 강하면(좋은 임베더 + 잘 쪼갠 청크) 정답이 이미 top에 있어 rerank가 바꿀 게 적다. MiniWatson은 nomic + recursive 청킹이 잘 동작해 쉬운 질문에서는 차이가 작았다.
- 차이는 질문 단어와 본문 표현이 어긋나는(어휘 불일치) 질문, 여러 청크가 비슷하게 매칭되는 질문에서 분명해진다.
- 비용: LLM rerank는 답변 생성과 별개로 LLM 호출이 1회 추가된다. 검색 1 + rerank 1 + 생성 1. 품질 이득과 지연을 저울질해야 한다.

## 5. 선택 가이드

핵심은 하나다. rerank 이득은 1차 검색이 약할 때만 나온다(4절). 1차가 강하면 안 붙이는 게 낫다.

| 상황 | 선택 | 근거 |
|---|---|---|
| 1차 검색이 이미 강함 (작은·깨끗한 코퍼스) | none | 3.1에서 차이 없음. 호출만 늘어남 |
| 후보에 비슷한 청크가 몰림 | mmr | 3.3. 중복일 때만 효과, 아니면 none과 동일 |
| 어휘 불일치·의도 구분이 필요 | llm | 3.2에서 #2를 끌어올림. LLM 호출 1회 추가 |
| 대규모·고정밀, Linux/GPU 환경 | cross | 토큰 상호작용까지 봄. 인텔 맥은 네이티브 부재로 폴백(2.4) |

비용 순서 none(0) < mmr(계산만) < llm(호출 1회) < cross(후보마다 추론), 정확도는 대체로 그 역순. 이 코퍼스에서는 none/mmr이면 충분했다. 교차 비교는 [DECISIONS.md](DECISIONS.md).

## 6. 함정: 라이브러리 API는 버전마다 다르다 (DJL CrossEncoder)

cross-encoder 구현 중 `CrossEncoderTranslatorFactory`를 import하니 "Cannot resolve symbol"이 떴다. 다른 블로그/예제에서 본 이름이라 맞다고 가정했던 것이 원인이었다.

추측 대신 실제로 쓰는 버전(DJL 0.30.0)의 javadoc을 직접 확인했더니:

- ai.djl.huggingface.translator 패키지에 CrossEncoderTranslatorFactory가 없었다. Factory는 FillMask, QuestionAnswering, TextClassification, TokenClassification, TextEmbedding에만 존재했다.
- CrossEncoderTranslator는 있지만 입력 타입이 String[]이 아니라 ai.djl.util.StringPair였다.
- 생성은 CrossEncoderTranslator.builder(HuggingFaceTokenizer) 정적 메서드로 한다.

그래서 Factory를 쓰지 않고, 토크나이저를 직접 만들어 Translator를 구성하고 Criteria.optTranslator(...)로 넘기는 방식으로 고쳤다. 입력도 new StringPair(question, passage)로 바꿨다.

교훈: 외부 라이브러리의 클래스명/시그니처는 버전마다 바뀐다. 기억이나 다른 버전 예제에 의존하지 말고, 프로젝트가 실제로 의존하는 버전의 javadoc(또는 jar 내용)을 확인한다.

## 7. 다음 단계

- 네 reranker(none/llm/mmr/cross) 정량 비교를 같은 질문 세트로 측정해 표로 정리
- cross-encoder의 지연을 재서 품질 대비 비용을 판단(후보 N 조정, 배치 추론 검토)
