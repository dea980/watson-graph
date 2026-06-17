# Embeddings (임베딩 모델 비교)

질의와 문서를 같은 벡터 공간으로 보내 cosine으로 검색하는 단계. RAG 파이프라인 load -> split -> embed -> store에서 embed에 해당한다. 임베딩 모델은 retrieval 품질의 1차 결정자다. 청킹·rerank·hybrid를 아무리 손봐도, 1차 검색이 정답 청크를 후보에 못 올리면 뒤 단계가 복구할 수 없다. 이 문서는 비교 방법과 측정 하네스를 기록한다. 측정값은 실행 후 채운다(아래 7절 표). 지어내지 않는다.

## 1. 왜 비교하나

임베딩 모델은 하나만 트레이드오프가 아니라 네 축이 한꺼번에 움직인다.

- 차원(dimension). 768 vs 1024는 표현력 차이지만, 동시에 저장량·연산량 차이다. 1024는 768 대비 벡터 1개당 +33% 바이트.
- 품질(recall). 모델마다 학습 데이터·목적이 달라 같은 질의에 다른 청크를 1등으로 올린다.
- 속도(ingest/query 지연). 큰 모델일수록 임베딩 1회가 느리다. 청크 수천 개를 넣는 ingest에서 누적된다.
- 다국어. 영어 중심 모델은 한국어 질의-한국어 문서 매칭이 약하다. 코퍼스에 비영어가 있으면 이 축이 변별력을 가른다.

"기본값 nomic이 최선인가"는 측정 없이 답할 수 없다. 그래서 후보 4개를 같은 코퍼스·같은 정답셋으로 돌려 비교한다.

## 2. 후보 4개 (차원별)

| 모델 | 차원 | 비고 |
|---|---|---|
| granite-embedding:30m | 384 | 가장 작고 빠름. 엣지/대량 후보 |
| nomic-embed-text | 768 | 이전 기본값(영어 중심) |
| granite-embedding:278m | 768 | 다국어 학습. **현재 기본값**(비교 승자, 7절) |
| mxbai-embed-large | 1024 | 표현력↑, 저장·연산 비용↑ |

384/768/1024 세 차원과 영어중심 vs 다국어 축을 한 번에 본다.

## 3. 공정 비교의 핵심 = 모델별 prefix 규약

임베딩 모델은 같은 텍스트라도 앞에 붙이는 지시 prefix가 다르고, 이걸 안 맞추면 모델 잘못이 아니라 사용법 잘못으로 점수가 깎인다. 공정 비교의 전제는 "각 모델을 그 모델이 의도한 방식으로 호출하는 것"이다.

규약(EmbeddingService.prefix()의 모델별 분기):

| 모델 | 쿼리 prefix | 문서 prefix |
|---|---|---|
| nomic | `search_query: ` | `search_document: ` |
| granite-embedding | (없음) | (없음) |
| mxbai | `Represent this sentence for searching relevant passages: ` | (없음) |
| 그 외(기본) | (없음) | (없음) |

nomic은 비대칭 prefix가 필수다(쿼리와 문서를 다른 토큰으로 구분). granite는 prefix 없이 쓰도록 설계됐다. mxbai는 쿼리에만 지시문을 붙이고 문서는 그대로 넣는다.

설계: 흩어진 리터럴 prefix를 호출부마다 두면 모델을 바꿀 때마다 여러 파일을 고쳐야 하고 한 곳을 빠뜨리면 조용히 틀린다. 그래서 EmbeddingService에 `embedQuery()`/`embedDocument()` 두 진입점을 두고, prefix 분기를 `prefix()` 한 곳에 중앙화했다. 호출부(RagService.embedQuery, IngestionService.embedDocument, SemanticChunker.embedDocument)는 prefix 리터럴을 모른다. 모델을 바꿔도 호출부는 안 바뀌고, prefix 규약은 prefix() 한 메서드만 손대면 된다.

## 4. 측정 방법 = 환경변수 swap, yaml 아님

application.yaml의 임베더는 `embed-model: ${OLLAMA_EMBED_MODEL:nomic-embed-text}`이다. yaml을 고치지 않고 `OLLAMA_EMBED_MODEL` 환경변수로 모델만 갈아끼워 측정한다.

왜 yaml을 안 고치나:

- 실험은 일시적이다. 측정이 끝나면 후보 3개는 버린다. 영구 설정 파일을 임시 실험으로 더럽히지 않는다.
- 재현·기록이 깔끔하다. 측정 모델명이 실행 명령 자체에 박혀 있어 "어떤 모델로 잰 숫자인가"가 명령 히스토리에 남는다. yaml을 매번 고치면 어느 시점에 무엇이었는지 추적이 흐려진다.
- 끝나면 승자만 yaml 기본값에 반영한다.

측정 1사이클(모델마다 반복):

```bash
pkill -f spring-boot                                   # 이전 인스턴스 종료
rm -f data/articles.json data/articles.parquet data/.articles.parquet.crc   # 차원 혼입 방지 wipe
OLLAMA_EMBED_MODEL=<model> ./mvnw spring-boot:run       # 측정 모델로 기동
bash eval/ingest_corpus.sh                              # 동일 코퍼스 고정 재현
python3 eval/run_eval.py                                # 정답셋 채점
```

wipe가 중요하다. 차원이 다른 벡터가 한 인덱스에 섞이면(예: 768 인덱스에 1024 벡터) 검색이 깨진다. 모델을 바꿀 때마다 저장 파일을 지워 차원 혼입을 막는다.

## 5. 코퍼스 (eval/golden.json, 35케이스)

| namespace | 케이스 | 언어/포맷 |
|---|---|---|
| default | 9 | 영어 Wikipedia + invoice(OCR) |
| IBM-blueprint-for-agentic-opeation | 11 | 영어 PDF |
| IBM-ceo-study-2026 | 4 | 영어 PDF |
| kr-bcg | 3 | 한국어 HTML |
| kr-hackathon | 2 | 한국어 DOCX |
| kr-medical | 2 | 한국어+영어 PPTX |
| kr-hwp | 2 | 한국어 HWP |
| kr-hwpx | 2 | 한국어 HWPX |

코퍼스 포맷은 PDF/HTML/DOCX/PPTX/PNG에 더해 HWP/HWPX(한글)까지 포함한다(추출 경로 상세는 [INGESTION-FORMATS.md](INGESTION-FORMATS.md)).

한국어 11케이스(kr-*, namespace 5개)가 다국어 모델(granite-embedding:278m) vs 영어중심(nomic)을 가르는 변별 축이다. 영어 24케이스에서 두 모델이 비슷해도, 한국어에서 갈리면 다국어 가치가 측정으로 드러난다.

## 6. 측정 3축

품질, 속도, 저장량을 따로 잰다. 한 축만 보면 트레이드오프를 못 본다.

- 품질: run_eval.py의 recall@k. 카테고리별(semantic/lexical/vocab-mismatch/discriminative-number 등)·언어별(영어/한국어)로 쪼개 본다. 평균 하나로 뭉개면 "영어는 같은데 한국어만 갈린다" 같은 신호가 사라진다.
- 속도: ingest 벽시계 시간. ingest_corpus.sh 전체를 도는 실측. 청크 수가 같으므로 모델 간 비교가 공정하다.
- 저장량: `data/articles.parquet` 파일 크기. 대략 차원 x 청크수 x 4바이트(float). 1024-dim은 768-dim 대비 +33%. 차원은 `/api/data/index/stats`의 `dimension` 필드로 확인한다(VectorIndex가 임베딩 실제 길이로 보고).

## 7. 결과 표 (측정값, 35케이스, rerank=mmr 기준)

동일 코퍼스(13 namespace)·동일 정답셋(golden.json 35)·모델별 prefix 규약 고정으로 측정. recall은 none/mmr 두 설정값. 영어 24케이스(default 9 + blueprint 11 + ceo 4), 한국어 11케이스(kr-* 5 namespace).

| 모델 | 차원 | recall(none/mmr) | 영어 24 | 한국어 11 | ingest(s) | parquet | 한 줄 평 |
|---|---|---|---|---|---|---|---|
| granite-embedding:30m | 384 | 31/35 · 31/35 (89%) | 22/24 | 9/11 | 미측정 | 미측정(최소) | 가장 작고 빠르나 한국어·복합 질의에서 2개 손해 |
| nomic-embed-text | 768 | 32/35 · 33/35 (94%) | 23/24 | 10/11 | 미측정 | 미측정 | 영어 강함, 한국어 1개(kr-bcg-junior) 놓침 |
| **granite-embedding:278m** | **768** | **34/35 · 34/35 (97%)** | 23/24 | **11/11** | 미측정 | 미측정 | **다국어 — 한국어 만점, 임베딩 천장** |

(주: 이 표는 약어 보정 전 측정값이다. 남은 유일 miss `ceo-caio-adoption`은 임베딩이 아니라 약어/정식명 불일치라, 이후 AcronymExpander([CHUNKING.md](CHUNKING.md) 2.5)로 닫아 granite-278m이 **35/35 (100%)**가 됐다. 즉 "임베딩으로 잡을 수 있는 천장 = 34/35, 나머지 1개는 청킹 보정으로"가 정량적으로 분리됐다.)
| mxbai-embed-large | 1024 | 33/35 · 33/35 (94%) | 23/24 | 10/11 | 미측정 | 미측정(최대 +33%) | 차원 최대인데 nomic과 동점, 한국어 똑같이 놓침 |

핵심 관찰:

- **영어는 4종 전부 23/24로 사실상 동점**(30m만 22). 공통 miss는 `ceo-caio-adoption` — "CAIO"(엔티티)와 "76%"(숫자)가 다른 청크에 흩어진 구조 문제라 어떤 임베더로도 못 잡는다. 임베딩이 아니라 chunking/멀티홉 영역.
- **변별은 전적으로 한국어에서 났다.** granite-278m만 한국어 11/11 만점. 나머지 셋은 `kr-bcg-junior`("주니어 개발자 수요 20% 감소")를 공통으로 놓침.
- **차원 ≠ recall.** mxbai는 최대 차원(1024)인데도 768짜리 nomic과 동점이고 granite-278m(768)에 진다. 한국어 케이스를 똑같이 놓치는 것까지 nomic과 동일 — 영어중심 학습이라 차원만 키워도 한국어가 안 살아남는다. 승부를 가른 건 차원이 아니라 **다국어 학습**이었다.

(ingest/parquet는 이번 사이클에서 따로 기록 안 함. 필요 시 `time bash eval/ingest_corpus.sh` + `ls -la data/articles.parquet`로 채운다. 6절 측정 3축 참고. 차원상 parquet은 30m < 768 두 모델 < mxbai 순, mxbai가 nomic 대비 +33%.)

## 8. 선택 가이드 (측정으로 확정)

| 차원/모델 | 권장 용도 | 근거(측정값) |
|---|---|---|
| 384 (granite 30m) | 속도·엣지·대량, 영어 위주 | recall 89%로 최저지만 가장 작고 빠름. 한국어·복합 질의 손해 감수 가능할 때 |
| 768 (nomic) | 영어 중심 균형 기본 | recall 94%, 영어 23/24. 한국어 비중 낮으면 가성비 |
| 1024 (mxbai) | (이 코퍼스엔 비권장) | 저장 +33%·연산↑인데 recall은 nomic과 동점. 차원 비용만큼의 이득 없음 |
| **다국어 (granite 278m)** | **한국어+영어 혼재 = 기본값** | **recall 97%, 한국어 11/11. 동일 768 차원으로 nomic보다 한국어 1개 우위, 비용 동급** |

결론: MiniWatson 코퍼스(한+영 혼재, IBM 한국 맥락)에선 **granite-embedding:278m이 기본값.** 같은 768 차원이라 nomic 대비 저장·연산 비용은 동급인데 한국어에서 정량 우위. 영어 전용 워크로드로 좁아지면 nomic, 엣지/대량이면 30m으로 내려간다. mxbai는 이 코퍼스에선 비용 대비 이득이 없어 탈락.

교차 비교는 [DECISIONS.md](DECISIONS.md) 4절.

## 9. 제약: pgvector는 차원 고정

pgvector 컬럼은 `vector(768)`로 차원이 고정돼 있어 384/1024를 같은 스키마에 못 넣는다. 따라서 차원 비교(384 vs 768 vs 1024)는 인메모리 VectorIndex(차원 무관)에서만 한다. 768 두 모델(nomic, granite-278m)만 나중에 pgvector로 옮겨 운영 환경에서 재확인한다. 인메모리는 재시작 시 재인덱싱이 필요하지만 차원 실험에는 그 유연성이 더 중요하다(DECISIONS.md 5절).
