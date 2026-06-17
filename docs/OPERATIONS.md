# Operations (운영)

데모를 운영으로 올릴 때의 배포·재임베딩·가용성·장애대응·프로덕션 준비를 기록한다. 핵심 원칙: **거버넌스(감사·격리)가 가용성을 죽이면 안 되고, 외부 의존(LLM·DB)은 언젠가 느려지거나 죽는다고 가정한다.**

## 1. 배포

프로필로 환경을 가른다.

| 프로필 | DB | Vector store | 보안 | 용도 |
|---|---|---|---|---|
| dev | H2 in-memory | 인메모리 | off | 로컬 개발·eval |
| demo | H2 file | 인메모리 | (선택) | 영속 데모 |
| prod | Postgres + pgvector | pgvector | on | 운영 |

컨테이너 이미지: 멀티스테이지 `Dockerfile`(maven 빌드 → Semeru JRE 실행). GitHub Actions가 main에서 멀티아치 이미지를 GHCR로 빌드·푸시(GitLab CI는 테스트 게이트만, 이미지 빌드는 추후).

운영 기동(env 주입 — 시크릿은 yaml에 커밋 안 함):
```bash
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DATABASE_URL=jdbc:postgresql://<host>:5432/miniwatson \
  -e DB_PASSWORD=<secret> \
  -e VECTOR_STORE=pgvector -e PGVECTOR_URL=jdbc:postgresql://<host>:5432/miniwatson \
  -e OLLAMA_URL=http://<ollama-host>:11434 \
  -e SECURITY_ENABLED=true -e SECURITY_MODE=jwt \
  ghcr.io/<org>/miniwatson:latest
```

주의: `-Djava.security.manager=allow`가 ENTRYPOINT에 박혀 있다(Java 21 + Hadoop/parquet 호환). 빼면 기동 실패.

### Oracle Cloud Always Free (ARM) 풀스택 배포 — $0

Ollama까지 포함한 풀스택을 영구 무료로 띄울 수 있는 유일한 현실해. ARM Ampere A1(최대 4 OCPU/24GB)이 무료라 app+postgres+ollama가 다 돌아간다.

1. **인스턴스**: VM.Standard.A1.Flex(ARM, Ubuntu 22.04/24.04), 4 OCPU/24GB. SSH 키 등록. (A1 용량 부족 뜨면 리전/AD 바꿔 재시도)
2. **네트워크 2겹**: VCN 보안목록에 8080 ingress 규칙 + **OS iptables도 열어야 함**(Oracle Ubuntu 이미지는 기본 차단):
   ```bash
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8080 -j ACCEPT
   sudo netfilter-persistent save
   ```
3. **Docker 설치**: `curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker $USER` (재로그인)
4. **배포 파일**: `git clone` 후 `cp .env.example .env`, `DB_PASSWORD` 채움.
5. **기동**: `docker compose -f docker-compose.prod.yml up -d` — 첫 실행은 ollama-init가 모델(수 GB) pull, ARM이라 시간 걸림(`logs -f`로 확인).
6. **접속**: `http://<퍼블릭IP>:8080`.

전제: ① CI가 **멀티아치(arm64 포함)** 이미지를 푸시했어야 함(ci.yml `platforms: linux/amd64,linux/arm64`). ② GHCR 패키지가 **public**이어야 `docker compose pull`이 인증 없이 됨(아니면 VM에서 `docker login ghcr.io`). DJL/PyTorch 크로스인코더는 ARM Linux 네이티브가 없을 수 있어 cross rerank는 폴백될 수 있음(mmr 기본이라 무관).

## 2. 재임베딩 파이프라인 (임베딩 모델 교체 시)

**임베딩 모델은 KB 불변 속성**이다. 모델을 바꾸면(예: granite-278m → 다른 768) 질의 벡터와 저장 벡터가 다른 공간이 돼 검색이 깨진다. 따라서 모델 교체 = **전 벡터 재임베딩**.

- **진실의 원천 = parquet(콜드 스토어).** pgvector/인메모리는 거기서 재구축 가능한 파생 인덱스. 그래서 재임베딩은 "원문 → 재청크 → 재임베딩 → parquet 재작성 → 인덱스 재구축" 순.
- **무중단**을 원하면 새 모델을 **새 namespace 버킷**에 적재하고, 검증(eval) 후 트래픽을 전환한다(블루-그린). 차원이 다르면(384/1024) pgvector(vector(768) 고정)엔 못 넣으니 인메모리 또는 별도 컬럼.
- 청킹·약어보정 로직을 바꿔도 동일하게 재임베딩 필요(저장본=임베딩본 원칙).

## 3. 가용성 하드닝 (적용됨)

외부 의존은 죽는다고 가정하고 격리한다.

- **감사 로그 fail-open** — `OllamaService`가 query_log 저장 실패 시 warn만 찍고 **답변은 반환**한다. 거버넌스 기록이 사용자 응답을 막으면 안 된다(실제로 H2 `DB_CLOSE_DELAY` 버그가 이 자리에서 500을 냈었다 — [DEBUGGING.md 4.7](DEBUGGING.md)). 더 나아가면 감사를 `@Async`로 비동기화해 지연까지 분리.
- **Ollama 타임아웃** — `OllamaService`(연결 5s/읽기 120s), `EmbeddingService`(5s/30s). 타임아웃 없는 RestTemplate은 Ollama가 멈추면 요청이 무한대기 → 스레드 고갈.
- **rerank fallback** — `RagService`가 rerank 실패(예: llm rerank의 Ollama 타임아웃) 시 벡터/하이브리드 후보 top-K로 graceful degradation. 재정렬이 죽어도 검색 답은 살린다.

## 4. CI/CD

- 게이트 = `./mvnw test`(53 단위테스트, 외부 의존 0). GitHub Actions + GitLab CI 양쪽 초록(같은 테스트, 이식성 증명). 이미지 빌드·푸시(멀티아치 GHCR)는 GitHub 쪽 main 한정. GitLab은 테스트 게이트만(빌드/배포는 추후).
- eval(golden)·B/C 라이브·pgvector recall은 Ollama/postgres/기동 앱 필요 → 기본 CI 밖(nightly/수동).
- 한계: 단위테스트는 순수 로직 회귀만 잡는다. 통합 버그(RRF id·pgvector·H2)는 통합테스트(서비스 필요) 영역.

CI에서 실제로 데인 함정 3개(로컬·문서엔 안 보이고 CI/빌드에서만 터짐 — [DEBUGGING.md](DEBUGGING.md)):
1. `.gitignore`의 `data/`(앵커 없음)가 소스 패키지 `com/.../data/`까지 무시 → 소스 미커밋 → CI 컴파일 실패. `/data/`로 앵커.
2. Dockerfile 인라인 `#` 주석을 COPY가 인자로 먹어 빌드 실패(한글 "이슈"가 경로로 둔갑). 주석은 자기 줄로만.
3. `mvnw` 실행권한 — CI/Docker에서 `chmod +x mvnw` 또는 `git update-index --chmod=+x`.

## 5. 장애 대응 런북

| 증상 | 1차 점검 | 대응 |
|---|---|---|
| 모든 응답 401, UI "Unexpected end of JSON" | `SECURITY_ENABLED`/`SECURITY_MODE` | 보안 의도면 키/토큰 첨부; 데모면 보안 off로 재기동 ([SECURITY.md §4.3](SECURITY.md)) |
| 답변 지연·행 | Ollama 상태(`curl :11434`), 타임아웃 로그 | Ollama 재시작; 타임아웃이 끊어주는지 확인 |
| 검색 0건 | `/api/data/index/stats` namespace별 벡터 수 | 코퍼스 적재됐나, namespace 일치하나 |
| pgvector recall 낮음 | 재구성 Article의 id, 차원 | RRF id 복원·차원 768 확인 ([PGVECTOR.md](PGVECTOR.md)) |
| 기동 실패 "database is empty" | H2 URL `DB_CLOSE_DELAY=-1` | dev yaml 확인 ([DEBUGGING.md 4.7](DEBUGGING.md)) |
| 감사 로그 안 쌓임 | `[audit]` warn 로그 | fail-open이라 정상 동작; query_log 테이블·DB 점검 |

## 6. 프로덕션 준비 체크리스트

- [ ] 시크릿 외부 주입(DB_PASSWORD·API키·JWT) — yaml에 커밋 금지, 미주입 시 기동 실패(fail-closed).
- [ ] `SECURITY_ENABLED=true` + 인증 모드 결정(API key/JWT). 운영 키는 IdP/시크릿 매니저.
- [ ] `eval.overrides.enabled=false`(prod) — 외부가 검색 전략 못 바꾸게.
- [ ] TLS(리버스 프록시), 레이트리밋(임베딩/LLM 비싸니 per-tenant).
- [ ] /h2-console·/swagger·actuator 잠금/제거.
- [ ] Ollama 타임아웃·감사 fail-open·rerank fallback 동작 확인(적용됨).
- [ ] 모니터링: 헬스체크, 에러율, p95 지연, Ollama 가용성.
- [ ] 재임베딩 절차 문서화(모델 교체 시) + parquet 백업.
- [ ] 감사 보존 정책, PII redaction 커버리지([SECURITY.md](SECURITY.md) Tier 2).

## 7. 남은 운영 과제 (Tier 2+)

- 감사 비동기화(`@Async`) — fail-open + 지연 분리.
- 프롬프트 인젝션 방어(시스템 프롬프트 경계), PII 커버리지, TLS·레이트리밋.
- 통합테스트 트랙(Ollama·postgres service container)으로 RRF·pgvector·context 회귀 잡기.
- 멀티테넌트 대규모면 pgvector 테넌트별 인덱스 + HikariCP 풀.
