# MiniWatson — Debugging Guide

> 부팅 안 됨 / 응답 이상함 / 모델 답 헤맴 / Parquet 깨짐 등 발생 시 이 문서를 첫 번째로 본다.

---

## 0. 30초 진단 트리

```
앱이 안 뜸 ─────────► 1 Boot Failures
앱은 떴는데 /api/rag/models 가 응답 안 함 ─► 2 Network / Port
ingest 가 실패 ─────────────────────► 3 Ingestion Failures
RAG 답이 이상하거나 느림 ────────────► 4 LLM / Embedding
Parquet 파일 관련 에러 ───────────────► 6 Parquet
governance log가 안 쌓임 ─────────────► 7 H2 / JPA
원인 모름 ────────────────────────────► 8 Observation Toolkit
```

---

## 1. Boot Failures

### 1.1 `IllegalAccessError`, `Subject.getSubject()` 관련 stacktrace

**원인**: Java 17+ 가 `Subject.getSubject(AccessControlContext)` 를 deprecated → Hadoop `UserGroupInformation` 가 호출 시 fail.

**해결**: `pom.xml` 의 `spring-boot-maven-plugin` 에 다음이 이미 박혀 있는지 확인:
```xml
<configuration>
    <jvmArguments>-Djava.security.manager=allow</jvmArguments>
</configuration>
```

직접 `java -jar` 로 실행할 땐:
```bash
java -Djava.security.manager=allow -jar target/miniwatson-0.0.1-SNAPSHOT.jar
```

### 1.2 `Failed to load article.avsc`
**원인**: `src/main/resources/article.avsc` 가 누락되었거나 build classpath에서 빠짐.

**해결**:
```bash
ls src/main/resources/article.avsc                  # 존재 확인
./mvnw clean compile                                # 재빌드
jar tf target/*.jar | grep article.avsc             # JAR 안에 들어갔는지
```

### 1.3 `Port 8080 already in use`
```bash
lsof -i :8080
kill -9 <PID>
# 또는
SERVER_PORT=8081 ./mvnw spring-boot:run
```

### 1.4 Lombok-related compile errors (`cannot find symbol getXxx`)
**원인**: IDE의 annotation processor 미활성 또는 `pom.xml`의 `annotationProcessorPaths` 빠짐.

**해결**:
- IntelliJ: `Preferences → Build → Compiler → Annotation Processors → Enable`.
- Lombok plugin 설치.
- `./mvnw clean compile` 로 CLI에서는 잘 도는지 우선 확인.

### 1.5 H2 `Database "mem:miniwatson" not found`
거의 발생 안 함 (in-memory는 자동 생성). 발생 시 `application-dev.yaml` 의 들여쓰기 확인 (`hibernate:` / `ddl-auto:` 줄).

---

## 2. Network / Port

### 2.1 `/api/rag/models` curl이 멈춤
- 앱이 진짜 떴는지 로그 확인 (`Started MiniwatsonApplication in X seconds`).
- 다른 인터페이스에서 listen 중일 수도: `lsof -nP -iTCP:8080 -sTCP:LISTEN`.

### 2.2 브라우저 dashboard가 안 뜸
- `http://localhost:8080/` 가 `index.html` 을 서빙해야 정상 (Spring Boot static resource).
- 콘솔 (DevTools) 에서 CORS / 404 / mixed content 확인.

---

## 3. Ingestion Failures

### 3.1 `403 Forbidden` from Wikipedia
**원인**: User-Agent 미설정.

**확인**: `IngestionService.ingest()` 안에 다음이 살아 있어야 함:
```java
headers.set("User-Agent",
   "MiniWatson/1.0 (https://github.com/dea980/miniwatson; kdea989@gmail.com)");
```

Wikipedia REST API 정책: <https://api.wikimedia.org/wiki/Wikimedia_REST_API#Terms_and_conditions>

### 3.2 `404 Not Found` from Wikipedia
- title 표기 확인. 공백은 `_`, 대소문자 정확히 (`Retrieval-augmented_generation`, not `retrieval-augmented_generation`).
- redirect 페이지는 summary endpoint가 따라가지 않을 수 있음.

### 3.3 ingest는 성공했는데 `/api/data/articles` 가 빈 배열
**원인**: 작업 디렉토리 차이.
- `./mvnw spring-boot:run` 은 보통 프로젝트 루트에서 실행 → `./data/articles.parquet` 가 거기 생성.
- IDE Run Configuration이 다른 cwd면 다른 위치에 파일 생성. **`pwd`** 확인.

```bash
find . -name "articles.parquet" -type f
```

### 3.4 `NullPointerException` 안에 `content_urls`
**원인**: `WikipediaResponse.ContentUrls` / `Desktop` 의 `static` 누락 → 역직렬화 실패 → null.

**확인**:
```java
public static class ContentUrls { ... }   // static
public static class Desktop     { ... }   // static
```

### 3.5 batch ingest가 중간에 멈춤
- 각 article은 try-catch로 격리되어 있어 한 건 실패해도 다음 진행 (`DataController.ingestBatch`).
- 그래도 멈춘다면 Ollama embedding 호출이 hang일 가능성 — 4.4 참조.

### 3.6 docx/pptx/xlsx ingest만 HTTP 500 — `NoSuchMethodError: UnsynchronizedByteArrayOutputStream.builder()`
**증상**: `/api/data/ingest-file`로 OOXML(docx/pptx/xlsx)을 넣으면 500. 로그의 `Caused by`에:
```
java.lang.NoSuchMethodError: org/apache/commons/io/output/UnsynchronizedByteArrayOutputStream.builder()
  at org.apache.poi.util.IOUtils...   (poi 5.2.5)
```
html/pdf/txt/md는 멀쩡한데 OOXML만 깨진다.

**원인**: POI 5.2.5(OOXML 파서)는 commons-io >=2.12의 `builder()`를 호출한다. 그런데 **Hadoop(parquet 의존)이 transitive로 commons-io 2.8.0**을 끌어와 classpath에서 우선됐다 → `builder()`가 없어 NoSuchMethodError. POI를 안 쓰는 포맷(html/pdf/txt/md)은 영향 없음.

**해결**: `pom.xml`에 **commons-io 2.18.0을 직접 의존성으로 고정**(직접 의존성이 transitive를 이긴다). 현재 박혀 있는지 확인:
```bash
grep -A2 'commons-io' pom.xml   # version 2.18.0
```

**교훈**: 500이 특정 포맷에만 나면 그 포맷 전용 파서의 transitive 의존성 충돌을 의심한다. `curl`로 status가 안 보이면 `server.error.include-message=always`나 앱 로그의 `Caused by`로 예외 클래스명을 먼저 본다. (`curl -s`는 HTTP 500에도 exit 0이라 스크립트가 성공으로 오인 — status 확인엔 `-f` 필요.)

---

## 4. LLM / Embedding

### 4.1 응답이 매우 짧음 ("...") 또는 비어 있음
**원인 1**: `num_predict` 부족.
```bash
OLLAMA_NUM_PREDICT=1500 ./mvnw spring-boot:run
```

**원인 2**: gemma3/4의 thinking 토큰이 토큰 예산 잡아먹음.
- `OllamaRequest.think = false` 가 살아 있는지 확인 (`OllamaService.ask()`).
- `think:true` 로 두면 latency 2~3× + 답변이 잘림.

### 4.2 모델이 컨텍스트 무시
- prompt 형식이 모델과 안 맞을 수 있음. `RagService` 의 prompt template 점검:
  ```
  Based on this context:
  - {title}: {summary}
  ...
  Answer the question concisely: {question}
  ```
- qwen / llama로 교체 시 `<|system|>...<|user|>...` 등의 chat template 필요할 수 있음.

### 4.3 `IndexOutOfBoundsException` in cosine
**원인**: 임베딩 차원 mismatch. 모델 교체 시 (e.g. `mxbai-embed-large` = 1024 dims) 기존 articles는 768 dims → 매칭 안 됨.

**해결**:
- 기존 Parquet 삭제: `rm ./data/articles.parquet`
- 새 embedding 모델로 ingest 다시.

### 4.4 Ollama hang / timeout
- `OllamaService`/`EmbeddingService` 의 `RestTemplate` 에 timeout 미설정. 무한 대기 가능.
- 임시 대응: Ollama 재시작 (`ollama stop && ollama serve`).
- 영구 대응 ([SDS.md TD-3](./SDS.md#7-known-tech-debt-구체적-위치--권장-조치)):
  ```java
  RestTemplate rt = new RestTemplateBuilder()
      .setConnectTimeout(Duration.ofSeconds(5))
      .setReadTimeout(Duration.ofSeconds(60))
      .build();
  ```

### 4.5 `nomic-embed-text` 응답이 빈 배열
- Ollama가 모델을 첫 호출에서 download/load 하느라 첫 요청 latency 큼. 두 번째부터 정상.
- `ollama pull nomic-embed-text` 수동 실행으로 미리 받아두기.

### 4.6 `vector.store=pgvector`로 켜니 context 기동 실패 (전략 패턴 누수)
증상:
```
APPLICATION FAILED TO START
Parameter 0 of constructor in com.miniwatson.service.IndexingService
  required a bean of type 'com.miniwatson.data.VectorIndex' that could not be found.
```
- 원인: `VectorStore`를 두 구현(VectorIndex/PgVectorStore)으로 만들고 `@ConditionalOnProperty`로 상호 배타시켰는데, `IndexingService` 생성자만 **구체 타입 `VectorIndex`**를 주입받고 있었다. pgvector 모드로 VectorIndex가 꺼지자 그 빈을 못 찾아 기동 실패. (HybridRetriever는 인터페이스 `VectorStore`로 받아 멀쩡했다.)
- 해결: IndexingService 생성자 파라미터를 `VectorStore`로 변경(필드는 이미 인터페이스였음). 한 단어.
- 교훈: **전략 패턴은 주입을 전부 인터페이스로 받아야 완성된다.** 구체 타입 주입이 한 곳이라도 남으면 구현 교체 시 거기서 깨진다. 컴파일·단위테스트로는 안 잡히고(빈 와이어링은 런타임 결정), **프로필을 바꿔 실제 기동해야** 드러난다 — pgvector 라이브 검증이 이 누수를 잡았다. 점검: `grep -rn "VectorIndex " src/main` 으로 구체 타입 주입이 더 없는지 확인.

### 4.7 H2 in-memory 테이블 증발 — `Table "QUERY_LOG" not found (this database is empty)`
증상: `/api/rag/ask`가 검색은 되는데 감사로그 저장 단계에서 500. `query_log` 테이블이 없다고 나온다.
- 원인: dev 프로필의 H2 URL이 `jdbc:h2:mem:miniwatson`(닫힘 지연 옵션 없음)이었다. H2 in-memory DB는 **마지막 커넥션이 닫히면 스키마째 소멸**한다. `ddl-auto=create-drop`이 기동 시 테이블을 만들지만, 유휴 시 커넥션 풀이 비면 DB가 증발 → 다음 요청에서 "database is empty".
- 왜 pgvector 모드에서 터졌나: PgVectorStore가 기동 시 442건을 postgres에 동기 적재하느라 **시작이 길어져**, 그 사이 H2 유휴 커넥션이 닫히고 in-mem DB가 소멸했다. 메모리 모드는 기동 직후 바로 eval이 돌아(커넥션 따뜻) 우연히 안 터졌을 뿐 — 잠복 버그였다.
- 해결: URL에 `;DB_CLOSE_DELAY=-1` 추가 → in-mem DB가 JVM 수명 동안 유지된다.
  ```yaml
  url: jdbc:h2:mem:miniwatson;DB_CLOSE_DELAY=-1
  ```
- 교훈: H2 mem은 데모 편의지만 커넥션 수명에 묶여 깨지기 쉽다. **기동이 길어지는 변경(대량 하이드레이션 등)이 잠복 타이밍 버그를 깨운다.** 영속이 필요하면 `DB_CLOSE_DELAY=-1` 또는 파일 모드.

### 4.8 pgvector recall이 인메모리보다 낮음 — 재구성 Article의 `id` 누락 → RRF 붕괴
증상: 같은 코퍼스·임베더·쿼리인데 pgvector 26/35, 인메모리 35/35. 벡터·정밀도·라운드트립·좀비 다 배제했는데도 차이.
- 원인: `HybridRetriever.rrf()`가 후보를 `Article.id`로 식별(`byId.put(a.getId(), ...)`, `score.merge(a.getId(), ...)`). PgVectorStore가 행에서 재구성한 Article이 `id`를 안 채움 → primitive `long` 기본값 **0** → pg 후보 전부 `id=0` 한 키로 붕괴 → 벡터 순위 소실. 인메모리는 원본 Article(진짜 id)이라 무사.
- 단서가 헷갈렸던 이유: 검색(`<=>`)은 정확하고 벡터도 동일해서 "retrieval은 맞는데 결과가 다른" 모순. double 재정렬을 넣어도 변화 없던 게 결정적 힌트였다(RRF가 리스트 순서를 0으로 뭉개니 앞단 재정렬이 무효).
- 해결: `article_vectors`에 `article_id` 컬럼 추가, 적재 시 원본 id 저장 / 검색 시 `a.setId(rs.getLong("article_id"))` 복원 → RRF 키가 keyword 인덱스와 매칭 → **35/35 회복**.
- 교훈: **DB 행에서 도메인 객체를 재구성할 땐 식별 키를 반드시 복원하라.** 안 그러면 검색은 맞아도 후처리(RRF·dedup·조인)가 조용히 깨진다. 단위테스트로 안 잡히고 통합 경로에서만 드러난다. 디버깅은 "그럴듯한 원인"이 아니라 **측정으로 하나씩 배제해 남은 것**을 따라가라.

### 4.9 좀비 JVM — "코드 고쳤는데 측정값이 미동도 없다"
증상: pgvector 디버깅 중 ef_search·double 재정렬 등 뭘 바꿔도 recall이 **한 글자도 안 변함**(계속 26/35). 몇 시간을 코드에서 헤맸는데 원인은 코드가 아니었다.
- 원인: `pkill -f spring-boot`가 spring-boot-maven-plugin이 **fork한 앱 JVM**을 놓쳐, 옛 코드의 앱이 8080을 잡고 계속 응답. 새로 띄운 줄 알았지만 측정은 좀비에게 갔다.
- 확정법: 빌드 마커 로그(예: init에 `log.info("BUILD-MARKER ...")`)를 박고 기동 로그에 뜨는지 본다. 소스=1, 컴파일된 .class=1(`grep -a -c MARKER target/.../X.class`)인데 **런타임 로그에만 없으면 좀비**.
- 해결: `pkill -9 -f java` + `jps`로 잔여 JVM 0 확인 + `lsof -nP -iTCP:8080 -sTCP:LISTEN` 비었는지 확인 후 재기동.
- 교훈: **"코드를 바꿨는데 결과가 미동도 없으면, 거의 항상 그 코드가 안 떠 있는 것."** 측정 전에 "최신 코드가 실제로 로드됐나"를 마커로 증명하라. (macOS `strings`는 .class의 `0xCAFEBABE`를 Mach-O fat 매직으로 오인해 깨지니 `grep -a` 사용.)

### 4.10 CI/빌드 함정 — 로컬·문서엔 안 보이고 CI에서만 터지는 것들
"내 머신에선 되는데"의 전형. 모두 fresh 체크아웃/빌드에서만 드러난다.

1. **`.gitignore`의 `data/`(앵커 없음)가 소스 패키지까지 무시.** 루트 런타임 `data/`만 막으려던 패턴이 **`src/.../com/miniwatson/data/`(자바 패키지)도 매칭** → 나중에 만든 `VectorStore.java`·`KeywordIndex.java`·`PgVectorStore.java`가 **커밋 자체가 안 됨** → CI `cannot find symbol`. 로컬은 디스크 파일로 컴파일돼 안 들킴. 해결: `/data/`로 **루트 앵커**. 점검: `git ls-files src/main/.../data/ | grep VectorStore`.
2. **Dockerfile 인라인 `#` 주석.** Dockerfile은 인라인 주석 미지원 — `COPY --from=build /app/app.jar app.jar  # 한글주석`에서 주석 단어가 **인자로 먹혀**, 에러 `"/이슈": not found`(한글 "이슈"가 경로로 둔갑). `RUN`은 셸이라 `#` OK지만 `COPY/EXPOSE/FROM`은 아님. 해결: **주석은 자기 줄로만**.
3. **`mvnw` 실행권한.** CI/Docker에서 `./mvnw`가 `Permission denied`(exit 126). 해결: `git update-index --chmod=+x mvnw` 또는 스크립트에 `chmod +x mvnw`.

교훈: 단위테스트 green ≠ CI green. **CI는 "추적 안 된 소스·빌드 환경·권한"을 잡는 또 다른 방어선**이다 — 로컬 성공이 CI를 보장하지 않는다.

---

## 5. Landmines — Don't Touch Without Reading

다음 코드들은 **이유가 있어서** 그렇게 되어 있습니다. 함부로 "리팩토링" 하지 마세요.

### 5.1 `OllamaRequest.think = false`
- gemma3/4 reasoning tokens가 num_predict 예산을 먹어 응답 잘림 + latency 3×.
- 다른 모델은 `think` 옵션을 무시하므로 항상 false로 두는 게 안전.

### 5.2 `IngestionService` User-Agent 헤더
- 빼면 Wikipedia 403. 운영 시 User-Agent 의 contact info 갱신 필요.

### 5.3 `Article.embedding` `@JsonProperty(WRITE_ONLY)`
- 빼면 모든 응답에 768-dim float 배열 노출 → 응답 50KB+. 클라이언트 부담 + storage detail 누출.

### 5.4 `WikipediaResponse.ContentUrls/Desktop static`
- non-static inner class는 Jackson이 outer instance 없이 새로 못 만듦 → 역직렬화 실패.

### 5.5 `ArticleParquetStore.saveAll()` 의 "delete then write"
- Parquet은 in-place mutation 불가. 의도된 동작.
- 단, 동시 쓰기 race는 [SDS TD-4](./SDS.md) 참조.

### 5.6 `AvroParquetReader` 에 `withDataModel()` **호출 금지**
- Parquet 1.14에서 reader의 withDataModel은 deprecated/NPE. writer만 사용.

### 5.7 `pom.xml` `-Djava.security.manager=allow`
- Java 21 + Hadoop 호환. 빼면 부팅 실패 (1.1).

### 5.8 `application.yaml` 의 `OLLAMA_*` env override
- 코드에 모델명/URL 박혀 있지 않음. 변경 시 `@Value` 키 일치 확인.

### 5.9 `pom.xml` 의 `commons-io 2.18.0` 직접 의존성
- 빼면 Hadoop의 transitive commons-io 2.8.0이 우선돼 POI(docx/pptx/xlsx)가 `NoSuchMethodError`로 깨짐 (3.6).

---

## 6. Parquet 트러블슈팅

### 6.1 `articles.parquet` 가 손상된 것 같다
```bash
# Avro/Parquet CLI로 확인
brew install parquet-tools
parquet-tools cat ./data/articles.parquet | head -1
parquet-tools schema ./data/articles.parquet
```

복구 불가하면 깔끔히 삭제 후 ingest 재실행:
```bash
rm ./data/articles.parquet ./data/.articles.parquet.crc
```

### 6.2 첫 ingest 후 두 번째 ingest에서 `Failed to delete existing Parquet file`
- 파일 핸들 leak 또는 권한 문제. 프로세스 모두 종료 후 권한 확인:
```bash
lsof | grep articles.parquet
chmod 644 ./data/articles.parquet
```

### 6.3 `loadAll()` 에서 `ClassCastException` on embedding
- Avro `array<float>` 가 reader 측에서 `List<?>` 로 들어옴. `ArticleParquetStore.loadAll()` 의 안전한 cast 패턴이 이미 적용됨:
  ```java
  if (embObj instanceof List<?> rawList) {
      for (Object item : rawList) {
          if (item instanceof Number num) embedding.add(num.floatValue());
      }
  }
  ```
  수정 시 이 패턴 유지.

### 6.4 `Could not load embedding column`
- Schema 불일치. `article.avsc` 와 실제 .parquet 의 schema 확인:
  ```bash
  parquet-tools schema ./data/articles.parquet
  ```
- Schema가 옛날 버전이면 [ERD 5.3 Migration recipe](./ERD.md#53-migration-recipe--article에-language-필드-추가) 참고.

---

## 7. H2 / JPA

### 7.1 governance log가 응답에 빈 배열
- `dev` 프로파일은 in-memory → 재시작하면 다 날아감.
- 영구 보관하려면 `--spring.profiles.active=demo`.

### 7.2 H2 콘솔 접속 안 됨
- `dev`/`demo` 프로파일에서만 `h2:console:enabled: true`.
- prod 프로파일에서는 활성화 안 됨 (보안).
- `application-dev.yaml` 의 들여쓰기 한 칸 어긋남 확인.

### 7.3 `Could not commit JPA transaction`
- H2 file 모드에서 다른 프로세스가 lock 잡고 있을 때. `AUTO_SERVER=TRUE` 가 설정되어 있으나, 그래도 충돌 시:
```bash
rm ./data/miniwatson.mv.db ./data/miniwatson.trace.db
```

---

## 8. Observation Toolkit

### 8.1 로그 레벨 조정
임시 (env 또는 args):
```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--logging.level.com.miniwatson=DEBUG \
                               --logging.level.org.springframework.web=DEBUG"
```

### 8.2 RAG 흐름 추적
`RagService` 에 이미 다음 로그가 있음:
```
RAG question: {}
Retrieved {} articles: {}
Augmented prompt length: {} chars
Ollama answer length: {} chars
```
`logging.level.com.miniwatson.service.RagService=DEBUG` 또는 `INFO` 활성.

### 8.3 SQL 로깅
`dev`/`demo` 프로파일에 `show-sql: true` 가 있어 query_log INSERT가 콘솔에 찍힘.

### 8.4 Ollama 자체 디버그
별도 터미널:
```bash
ollama logs        # macOS: ~/.ollama/logs/server.log
# 또는
OLLAMA_DEBUG=1 ollama serve
```

### 8.5 네트워크 패킷 확인
```bash
# Ollama 호출 trace
curl -v http://localhost:11434/api/tags

# Wikipedia 호출 reproduce
curl -v -H "User-Agent: MiniWatson/1.0 (test)" \
  "https://en.wikipedia.org/api/rest_v1/page/summary/RAG"
```

### 8.6 Parquet 빠른 inspect
```bash
parquet-tools rowcount ./data/articles.parquet
parquet-tools head -n 1 ./data/articles.parquet
parquet-tools meta ./data/articles.parquet  # SNAPPY 압축 통계
```

---

## 9. "이런 증상은 99% 이거다" cheat sheet

| 증상 | 가장 흔한 원인 |
|---|---|
| `/api/rag/models` 자체가 응답 없음 | 앱이 떠 있지 않거나 다른 포트 |
| ingest 403 | User-Agent 미설정 (코드 회귀) |
| ingest 404 | title 오타 / 대소문자 |
| RAG 답이 잘리거나 짧음 | `num_predict` 부족 또는 `think:true` 회귀 |
| RAG 응답 매우 느림 | Ollama 첫 호출 cold load |
| `No articles in knowledge base` | Parquet 파일 없음 (cwd 다름 or 삭제됨) |
| governance logs 텅 비어 있음 | dev 프로파일에서 재시작 후 in-mem 초기화 |
| `IndexOutOfBoundsException` (cosine) | embedding 모델 교체 후 기존 데이터와 차원 mismatch |
| Parquet write 실패 | 직전 실행이 파일 핸들 안 닫음 (강제 종료) |
| Lombok getter 없다고 컴파일 fail | annotation processor 미설치 |

---

## 10. 마지막 수단 — Clean Reset

데이터 모두 날리고 클린 상태로:
```bash
./mvnw clean
rm -rf data/articles.parquet data/.articles.parquet.crc \
       data/miniwatson.mv.db data/miniwatson.trace.db \
       target/
ollama stop
ollama serve &
./mvnw spring-boot:run
```

위로 안 풀리면:
1. `git status` — 본인이 모르게 건드린 파일 확인.
2. `git stash` 후 last known-good commit 으로 checkout.
3. README `Quick Start` 의 prerequisites 처음부터 다시.
