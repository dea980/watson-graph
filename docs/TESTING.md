# Testing

수동 curl / 평가 하니스(EVALUATION.md) 외에, 순수 로직에 대한 JUnit5 단위 테스트를 둔다. 리팩터(VectorStore 추상화 등)의 안전망이자, 회귀 방지용이다.

## 실행

```bash
./mvnw test                                  # 전체
./mvnw test -Dtest=PiiRedactionServiceTest   # 하나만
```

테스트는 대상과 같은 패키지를 미러링한다(Maven 규약): `src/test/java/com/miniwatson/<pkg>/<Class>Test.java`. 같은 패키지면 package-private 멤버 접근이 가능하고 찾기도 쉽다.

## 무엇을 테스트하나 (순수 로직 우선)

Spring 컨텍스트 없이 new로 만들어 빠르게 검증한다. 외부 의존(Ollama, DB)이 없는 결정적 로직만 대상으로 한다.

| 테스트 | 대상 | 검증 |
|---|---|---|
| RecursiveChunkerTest | RecursiveChunker | 짧은 글=1청크, 긴 글은 분할, 모든 청크 <= maxSize, 빈 청크 없음 |
| KeywordIndexTest | KeywordIndex (BM25) | 정확 토큰이 상위 랭크, namespace 격리, 매칭 없으면 빈 결과 |
| PiiRedactionServiceTest | PiiRedactionService | 이메일/전화/SSN 마스킹, 정상문 불변, null 처리 |
| EmbeddingServiceTest | EmbeddingService.prefixFor | 모델별 prefix 규약 고정(nomic/granite-embedding/mxbai/모르는 모델/null+대소문자) — 공정 비교의 정확성 |
| HwpExtractorTest | HwpExtractor | .hwp/.hwpx 추출이 비어있지 않은지 스모크 — sample 파일 의존이라 assumeTrue로 skip 가능한 integration |
| IngestionServiceTest | IngestionService.formatOf | 확장자->추출기 라우팅(.hwp/.hwpx->전용, 그 외->Tika), 대소문자·null·복합확장자 — HWP가 Tika로 새는 회귀 방지 |

KeywordIndex는 생성자에 ArticleRepository가 필요한데 hydrate에서만 쓰이므로, 빈 익명 구현(stub)을 넘기고 add()로 직접 문서를 채워 테스트한다(@PostConstruct는 단위 테스트에서 호출되지 않음).

prefixFor는 원래 private였으나, 테스트 가능성을 위해 package-private static 순수 함수로 뺐다(`record Prefix(q, d)` 반환). 같은 패키지의 EmbeddingServiceTest가 Spring 컨텍스트·Ollama 없이 매핑만 직접 검증한다 — 이 prefix가 틀어지면 에러 없이 검색 점수만 조용히 깎이는, 공정 비교의 핵심 로직이라 단위 테스트로 못 박았다.

HwpExtractorTest는 외부 서버를 띄우지 않지만 `sample/`의 실제 .hwp/.hwpx 파일에 의존하는 가벼운 integration이다. 파일이 없으면 실패가 아니라 assumeTrue 가드로 skip한다 — 아래 "범위 밖"의 @SpringBootTest 통합 테스트(외부 의존 기동 필요)와는 결이 다르다.

IngestionServiceTest는 `formatOf(filename)` 라우팅만 순수 검증한다(파일·Tika·HWP 라이브러리 불필요). 이 분기는 실제로 한 번 깨졌었다 — `ingestText`가 `extractText` 결과를 다시 `tika.parseToString`으로 덮어써 .hwp가 Tika로 새고 "추출 실패"가 났다. 분기를 `formatOf` 순수 함수로 분리하고 덮어쓰기를 제거한 뒤, 이 테스트로 회귀를 막는다.

## 테스트가 실제 버그를 잡은 사례: 한국 전화번호 PII 미마스킹

PiiRedactionServiceTest.masksPhoneAndSsn이 처음에 실패했다. `010-1234-5678`(3-4-4 형식)이 [PHONE]으로 마스킹되지 않았다.

원인: PHONE 정규식이 미국식 3-3-4(`\d{3}-\d{3}-\d{4}`)를 가정해, 한국 휴대폰 3-4-4를 못 잡았다. 즉 감사 로그에 한국 전화번호가 그대로 남는 governance 구멍이었다.

수정: 구분자 기반의 더 일반적인 패턴으로 교체해 3-4-4와 3-3-4를 모두 잡도록 했다.

```java
put("[PHONE]", Pattern.compile("\\b\\d{2,4}[ -]\\d{3,4}[ -]\\d{4}\\b"));
```

교훈: 수동 테스트(curl)에서는 미국식 번호만 넣어봐서 통과처럼 보였지만, 단위 테스트가 한국 번호 형식의 미지원을 드러냈다. "테스트는 통과시키려고 쓰는 게 아니라, 가정이 틀린 곳을 드러내려고 쓴다."

## 범위 밖 (의도적)

- @SpringBootTest 통합 테스트(MiniwatsonApplicationTests)는 Ollama/DB 등 외부 의존을 띄워야 해 CI에서 불안정하다. 단위 테스트와 분리해 다룬다.
- HybridRetriever의 RRF 융합은 VectorStore + KeywordIndex 협력이 필요해 단위 테스트 비용이 크다. 현재는 EVALUATION.md의 엔드투엔드 평가로 간접 검증한다(향후 RRF 순수 함수만 분리해 단위 테스트 가능).
- Ollama 호출(OllamaService.generate), 임베딩(EmbeddingService)은 외부 서비스라 단위 테스트에서 제외하거나 mock이 필요하다.

## 다음 단계

- RRF 점수 계산을 순수 함수로 추출해 단위 테스트
- PII 정규식 케이스 추가(국제번호, 마스킹 오탐 경계)
- 통합 테스트는 별도 프로파일/태그로 분리해 선택 실행
