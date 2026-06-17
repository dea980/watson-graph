# Chunking 전략 비교 테스트 가이드

세 가지 분할 전략(fixed / recursive / semantic)을 같은 문서에 적용해 청크 수, 경계 품질, 속도를 비교한다. 결과는 5절 표에 채운다.

## 0. 준비

- 테스트 문서: sample/rag-notes.txt (주제가 개념 / 4단계 파이프라인 / 장점·비용으로 나뉘어 경계 비교에 적합)
- 전략은 시작 시 한 번 결정되므로, yaml을 바꿀 때마다 반드시 재시작한다.
- 전략마다 다른 namespace에 넣어 서로 섞이지 않게 한다.
- 명령은 프로젝트 루트(~/Downloads/miniwatson)에서 실행한다고 가정한다.

전략 전환 위치 (src/main/resources/application.yaml):

```yaml
chunking:
  strategy: fixed     # fixed | recursive | semantic
```

## 1. fixed

- yaml: `strategy: fixed` -> 저장
- 재시작: `./mvnw spring-boot:run`
- 업로드 + 청크 확인:

```bash
time curl -s -X POST \
  -F "file=@sample/rag-notes.txt" \
  -F "namespace=test-fixed" \
  http://localhost:8080/api/data/ingest-file | python3 -m json.tool | grep '"title"' | wc -l

curl -s "http://localhost:8080/api/data/articles?namespace=test-fixed" \
  | python3 -c "import sys,json; [print('---', a['title'], '\n', a['summary'][:200]) for a in json.load(sys.stdin)]"
```

- 첫 명령의 출력 숫자 = 청크 수, time 결과 = 소요 시간
- 둘째 명령 = 각 청크 앞 200자 (경계가 단어 중간에서 끊기는지 확인)

## 2. recursive

- yaml: `strategy: recursive` -> 저장
- 재시작
- 업로드 (namespace=test-recursive):

```bash
time curl -s -X POST \
  -F "file=@sample/rag-notes.txt" \
  -F "namespace=test-recursive" \
  http://localhost:8080/api/data/ingest-file | python3 -m json.tool | grep '"title"' | wc -l

curl -s "http://localhost:8080/api/data/articles?namespace=test-recursive" \
  | python3 -c "import sys,json; [print('---', a['title'], '\n', a['summary'][:200]) for a in json.load(sys.stdin)]"
```

## 3. semantic

- yaml: `strategy: semantic` -> 저장
- 재시작
- 업로드 (namespace=test-semantic):

```bash
time curl -s -X POST \
  -F "file=@sample/rag-notes.txt" \
  -F "namespace=test-semantic" \
  http://localhost:8080/api/data/ingest-file | python3 -m json.tool | grep '"title"' | wc -l

curl -s "http://localhost:8080/api/data/articles?namespace=test-semantic" \
  | python3 -c "import sys,json; [print('---', a['title'], '\n', a['summary'][:200]) for a in json.load(sys.stdin)]"
```

semantic은 문장마다 임베딩을 호출하므로 time 값이 fixed/recursive보다 클 것으로 예상된다. 작은 문서라 차이가 작을 수 있으니, 큰 문서로도 한 번 재보면 비용 차이가 분명해진다.

## 4. 인덱스 확인

세 namespace에 각각 벡터가 들어갔는지:

```bash
curl -s http://localhost:8080/api/data/index/stats | python3 -m json.tool
```

namespaces에 test-fixed, test-recursive, test-semantic이 각각 청크 수만큼 보이면 정상.

## 5. 비교 결과 (sample/rag-notes.txt, max-size 250, 약 850자)

| 전략 | 청크 수 | ingest 시간 | 경계 품질 (관찰) |
|---|---|---|---|
| fixed | 8 | 즉시 | 단어 중간에서 잘림. overlap 150으로 청크 간 약 150자 중복 |
| recursive | 6 | 즉시 | 줄/문장/문단 경계 보존. 단어 안 잘림, 중복 없음 |
| semantic | 5 | 3.45s | 의미(주제) 단위로 묶음. 문장 분리 정규식 부작용으로 일부 어색한 경계 |

관찰 메모:

- fixed: "knowledge base," 다음이 "ge model"로 단어 중간이 잘렸다. overlap 때문에 각 청크가 앞 청크 끝 일부를 다시 포함한다. 가장 단순하고 빠르지만 경계 품질이 가장 낮다.
- recursive: 제목만 한 청크, "A typical RAG pipeline... 1. 2. 3." 같은 문단을 한 청크로 묶는 등 줄 단위 경계가 살아있다. 비용 0으로 즉시 끝난다.
- semantic: 제목+정의(#1), RAG 동작 설명 문단(#2), 혜택 문단(#5)을 의미 단위로 잘 뭉쳤다. 다만 "steps: 1."에서 어색하게 끊긴 청크가 있는데, 문장 분리를 마침표 기준으로 해서 "1."을 문장 끝으로 오인한 탓이다. 850자 문서에 3.45초가 걸려, 문장마다 임베딩하는 비용이 그대로 드러났다(큰 문서는 분 단위 예상).

결론: recursive를 기본으로 둔다. 비용 0에 경계 품질이 충분히 좋다. semantic은 의미 단위 묶음이 우수하지만 임베딩 비용이 커서 옵션으로 남긴다. fixed는 가장 단순한 baseline으로, 경계 품질이 낮아 실사용에는 권장하지 않는다.

## 6. 정리

비교가 끝나면 테스트 namespace는 지워도 된다. 각 청크를 삭제하거나, 저장 데이터(data/articles.json, data/articles.parquet)를 비우고 재시작한다.

측정값과 관찰을 CHUNKING.md의 2.2절(semantic)과 비교표에 옮긴다.
