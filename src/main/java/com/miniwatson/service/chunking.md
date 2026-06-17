# Chunking (문서 분할)

긴 문서를 검색 단위(chunk)로 쪼개는 단계. RAG 파이프라인의 load -> split -> embed -> store 중 split에 해당한다. 이 문서는 MiniWatson에서 실제로 구현하고 검증한 내용만 기록한다.

## 1. 왜 필요했는가 (실제로 겪은 문제)

처음에는 파일 ingest가 문서 전체를 article 1개 = embedding 1개로 저장했다. 90k자짜리 IBM 리포트 PDF를 이렇게 넣었더니 retrieval이 깨졌다. 확인된 원인은 두 가지다.

- 임베더 토큰 한도. nomic-embed-text는 약 8k 토큰까지만 본다. 90k자 문서는 앞부분만 임베딩되고 뒷부분은 잘려서 벡터에 반영되지 않았다. 문서 뒷부분(임팩트 수치 등)을 묻는 질의가 매칭되지 않았다.
- 단일 벡터로는 부분 질의를 못 잡는다. 문서 전체를 한 벡터로 뭉개니 특정 구절과의 유사도가 흐려지고, 짧고 선명한 다른 문서가 더 가깝게 잡히는 역전이 일어났다.

해결: 문서를 작은 청크로 나누고 각 청크를 별도 article + 별도 embedding으로 저장. 같은 PDF가 청크 101개로 쪼개졌고, 올바른 namespace로 질의하니 정확한 청크(silos 관련 #3, #11)를 찾아 82% 근거까지 답했다.

## 2. 구현 (전략 패턴)

chunking은 ingest와 별개 관심사라 인터페이스로 분리했다.

```
Chunker (interface)
├─ FixedChunker      @Component("fixed")        N자마다 분할 + overlap       [baseline]
├─ RecursiveChunker  @Component("recursive")    순수 문자열 로직, 의존성 0    [기본]
└─ SemanticChunker   @Component("semantic")     EmbeddingService 주입        [옵션]
```

IngestionService는 모든 Chunker 빈을 `Map<String, Chunker>`로 주입받고 설정값으로 구현체를 고른다.

```yaml
chunking:
  strategy: recursive   # semantic 으로 바꾸면 전환 (재시작 필요)
```

```java
this.chunker = chunkers.getOrDefault(strategy, chunkers.get("recursive"));
```

새 전략을 추가할 때 구현체만 늘리면 되고 기존 코드는 안 건드린다.

### 2.1 FixedChunker (baseline)

maxSize자마다 자르고 overlap(150자)으로 청크 간 일부를 겹친다. 가장 단순하고 빠르지만 단어/문장 중간이 잘린다. 출발점으로만 둔다.

### 2.2 RecursiveChunker (기본)

구분자 우선순위(`\n\n` -> `\n` -> `". "` -> `" "`)로 자르되, 최대 크기를 넘으면 다음 구분자로 더 잘게 내려간다. 문단을 최대한 보존하면서 크기를 제한한다. 비용이 0이라(문자열 연산만) ingest가 즉시 끝난다. IBM 리포트 PDF가 이 방식으로 101청크로 분할됐다.

### 2.3 SemanticChunker (옵션)

길이가 아니라 의미로 경계를 정한다.

- 문장 단위 분리
- 각 문장을 임베딩
- 인접 문장 간 cosine 유사도 계산
- 유사도가 percentile 임계값 아래로 떨어지는 지점(주제 전환) = 경계
- 경계 사이 문장을 묶되 최대 크기를 넘으면 강제 분할

의미 단위 묶음은 우수하나 문장 수만큼 임베딩을 호출해야 해서 비용이 크다. 큰 문서일수록 ingest가 느려진다.

### 2.4 실측 비교 (sample/rag-notes.txt, max-size 250, 약 850자)

| 전략 | 청크 수 | ingest 시간 | 경계 |
|---|---|---|---|
| fixed | 8 | 즉시 | 단어 중간 잘림("knowledge base," -> "ge model"), overlap으로 약 150자 중복 |
| recursive | 6 | 즉시 | 줄/문장/문단 경계 보존, 단어 안 잘림, 중복 없음 |
| semantic | 5 | 3.45s | 의미(주제) 단위로 묶음. 문장 분리를 마침표 기준으로 해서 "1."을 문장 끝으로 오인하는 부작용 |

결론: recursive를 기본으로 둔다. 비용 0에 경계 품질이 충분하다. semantic은 의미 묶음이 가장 좋지만 850자 문서에도 3.45초가 걸려(문장마다 임베딩) 큰 문서는 분 단위로 느려지므로 옵션으로 남긴다. fixed는 baseline으로, 경계 품질이 낮아 실사용에는 권장하지 않는다.

max-size와 strategy는 둘 다 설정으로 조절한다.

```yaml
chunking:
  strategy: recursive    # fixed | recursive | semantic
  max-size: 1000         # 비교 테스트 시 250 등으로 낮춰 작은 문서도 쪼개 본다
```

## 3. 저장 형태

문서 1개 -> 청크 N개 -> Article N개. title에 번호를 붙인다.

```
the-blueprint-for-agentic-operations-report.pdf #1
the-blueprint-for-agentic-operations-report.pdf #2
...
```

ingestText는 List을 반환한다. 같은 baseTitle(번호 제거)로 묶어 문서 단위 작업(요약)을 처리하고, 같은 namespace에 같은 baseTitle이 이미 있으면 재삽입하지 않는다(dedup).

## 4. 실전에서 발견하고 고친 함정

### 4.1 namespace 불일치 (검색 안 됨의 진짜 원인)

긴 PDF가 검색되지 않아 처음엔 청킹 부재를 의심했으나, 실제 원인은 청크가 default가 아닌 다른 namespace(IBM-blueprint-for-agentic-opeation)에 저장된 것이었다. Ask는 namespace를 안 보내면 default만 검색하므로, 다른 테넌트에 저장된 문서는 후보에 들지 않는다. index/stats로 namespace별 벡터 수를 확인해 원인을 특정했다.

교훈: 검색 결과가 비면 코드/임베딩을 의심하기 전에 /api/data/index/stats에서 어느 namespace에 몇 개의 벡터가 있는지부터 본다.

### 4.2 RAG != 단일 문서 요약

"이 문서를 요약해줘"를 Ask(RAG)에 넣으면 동작하지 않았다. RAG는 질의를 임베딩해 유사한 청크를 top-k로 검색할 뿐, 특정 문서를 통째로 요약하지 않는다. summarize는 ID로 문서를 직접 꺼내 전문을 LLM에 주는 별도 경로(POST /api/data/summarize/{id})로 분리했다. 검색은 질문->조각, 요약은 ID->전문으로 데이터 흐름이 다르다.

### 4.3 감사 로그 컬럼 오버플로

요약 시 문서 전문을 프롬프트에 넣자 audit 로그의 augmentedPrompt 컬럼(H2 기본 VARCHAR 4000)을 초과해 500 에러가 났다. 컬럼을 CLOB(@Lob)으로 바꾸고 입력 길이도 8000자로 캡했다. 감사 로그는 길이로 실패해서는 안 된다는 governance 원칙.

### 4.4 PDF 추출 잡음

PDF 폼 필드("Button 4: Page 4:" 같은 줄)가 마지막 청크들로 들어왔다. 검색 품질을 위해 인덱싱 전에 필터링하면 좋다(미적용, 관찰만 함).

## 5. 다음 단계

- reranking. 청크가 101개로 늘었으므로 1차로 넉넉히 top-N 후보를 뽑고 reranker로 최종 top-K만 LLM에 전달 (기본 rerank = mmr)
- semantic 비용 최적화. 문장 임베딩 배치 처리, 또는 일부 문서에만 선택 적용
- 문장 분리 개선. 마침표 기준의 부작용("1." 오인)을 줄이는 더 정교한 sentence splitter
