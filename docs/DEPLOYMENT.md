# Deployment — Postgres + pgvector (Podman)

MiniWatson은 dev/demo에서 H2를 쓰고, prod 프로필에서 PostgreSQL + pgvector를 쓴다. 로컬에서는 Podman 컨테이너로 Postgres를 띄운다. 이 문서는 셋업 절차와 실제로 겪은 함정을 기록한다.

## 1. 프로필별 저장소

| 프로필 | DB | 영속성 | 용도 |
|---|---|---|---|
| dev | H2 in-memory | 재시작 시 초기화 | 빠른 개발 |
| demo | H2 file (./data) | 디스크 유지 | 로컬 데모 |
| prod | PostgreSQL + pgvector | 컨테이너 볼륨 | 실제 배포 |

## 2. Podman으로 Postgres + pgvector 띄우기

pgvector 확장이 내장된 공식 이미지를 쓴다(직접 빌드 불필요).

```bash
podman machine start

podman run -d --name miniwatson-pg \
  -e POSTGRES_DB=miniwatson \
  -e POSTGRES_USER=miniwatson \
  -e POSTGRES_PASSWORD=miniwatson \
  -p 5432:5432 \
  pgvector/pgvector:pg16

# 확장 활성화
podman exec -it miniwatson-pg psql -U miniwatson -d miniwatson \
  -c "CREATE EXTENSION IF NOT EXISTS vector; SELECT extversion FROM pg_extension WHERE extname='vector';"
```

Docker를 쓴다면 `podman`을 `docker`로 바꾸면 동일하다. Podman은 daemonless/rootless이고 RedHat(IBM) 생태계에 맞아 선택했다.

## 3. Spring 연결 (application-prod.yaml)

```yaml
spring:
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/miniwatson}
    username: ${DB_USER:miniwatson}
    password: ${DB_PASSWORD:miniwatson}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
```

pom.xml에 드라이버:

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

실행:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

기동 로그에 `Database version: 16.x`와 `create table query_log/document_catalog`가 보이면 정상. 질의 후 재시작해도 데이터가 남으면(아래) 영속화 확인.

```bash
podman exec -it miniwatson-pg psql -U miniwatson -d miniwatson -c "\dt"
podman exec -it miniwatson-pg psql -U miniwatson -d miniwatson -c "SELECT COUNT(*) FROM query_log;"
```

## 4. 함정 (실제로 겪은 것)

### 4.1 role does not exist — 자격증명이 아니라 포트 점유였다

`FATAL: role "miniwatson" does not exist`가 Spring과 DBeaver 양쪽에서 계속 났다. user 철자를 의심해 한참 헤맸지만, `podman exec ... psql`로 컨테이너에 직접 들어가면 user는 멀쩡했다(`SELECT current_user` = miniwatson).

진짜 원인: 호스트에 brew로 설치된 PostgreSQL(@16, @17)이 이미 5432를 점유하고 있었다. 그래서 Spring/DBeaver는 우리 컨테이너가 아니라 호스트의 다른 Postgres에 붙었고, 거기엔 miniwatson role이 없었다. 로그의 `Database version: 13.0`(컨테이너는 16)이 결정적 단서였다.

해결: 호스트 Postgres를 정지(`brew services stop postgresql@16/@17`)해 5432를 비우고 컨테이너가 차지하게 했다(또는 컨테이너를 다른 포트로 매핑).

교훈: 연결 에러가 자격증명처럼 보여도, 같은 포트에 다른 서버가 떠 있지 않은지(`lsof -i :5432`)와 서버 버전이 기대와 같은지부터 확인한다.

### 4.2 CLOB 타입은 H2 전용 — Postgres엔 없다

query_log 테이블 생성이 `ERROR: type "clob" does not exist`로 실패했다. QueryLog.augmentedPrompt에 `@Column(columnDefinition = "CLOB")`로 DB 타입을 하드코딩했는데, CLOB은 H2 타입이고 Postgres엔 없다.

해결: 특정 DB 타입을 박지 않고 `@Lob`만 사용. Hibernate가 각 DB에 맞는 타입을 고른다(H2=CLOB, Postgres=text/oid).

```java
@Lob
private String augmentedPrompt;   // columnDefinition = "CLOB" 제거
```

교훈: 엔티티에 DB별 타입을 하드코딩하면 DB 전환 시 깨진다. 표준 어노테이션(@Lob)으로 추상화한다.

### 4.3 Apple Silicon인데 터미널이 Rosetta(x86)

`arch`가 i386으로 나오고 `brew install`이 "Cannot install under Rosetta 2 in ARM default prefix"로 거부했다. 이 맥은 M2(arm64)인데 터미널이 x86 에뮬레이션으로 돌고 있었다. 그 결과 ARM 네이티브를 못 찾아 brew/podman/DJL이 꼬였다(DJL cross-encoder 폴백의 원인일 수도).

해결: `~/.zprofile`에 i386이면 arm64 zsh로 재실행하는 가드를 넣었다.

```bash
if [ "$(arch)" = "i386" ]; then exec arch -arm64 /bin/zsh -l; fi
```

## 5. 현재 범위와 다음

- 지금 prod에서 Postgres로 옮긴 것은 JPA 영속 데이터(query_log, document_catalog)다. 벡터 검색(VectorIndex)은 아직 인메모리다.
- 다음: pgvector를 실제 검색에 쓰는 PgVectorStore 구현(VectorStore 인터페이스 구현체). embedding 컬럼 + `<->` 연산자로 kNN. 그러면 벡터도 Postgres에 영속되고 재시작 후 재인덱싱이 불필요해진다.
- 컨테이너 오케스트레이션: app + Postgres + Ollama를 compose로 묶는 것은 후속 작업.
