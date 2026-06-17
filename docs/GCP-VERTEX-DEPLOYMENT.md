> 상태: 미채택(검토 기록). 벤더 중립 방식으로 전환됨 → [PHASE0-DEPLOYMENT.md](PHASE0-DEPLOYMENT.md) 참고. 이 문서는 GCP 경로 비교 근거로 남겨둔다.

# Phase 0 — GCP + Vertex AI 배포 가이드 (미채택)

LLM을 Ollama 자체호스팅에서 GCP Vertex AI 관리형 추론으로 옮기고, 앱과 pgvector를 Compute Engine에 올리는 단계별 절차. 각 단계에 **왜 필요한지 / 작업 / 검증**을 붙였다. 코드는 직접 작성하고, 이 문서는 순서와 검증 기준을 잡는 용도다.

상위 맥락(클라우드 선택 근거)은 [CLOUD-DEPLOYMENT.md](CLOUD-DEPLOYMENT.md), 로컬 셋업은 [DEPLOYMENT.md](DEPLOYMENT.md), 임베딩 근거는 [EMBEDDINGS.md](EMBEDDINGS.md), 보안은 [SECURITY.md](SECURITY.md).

> 왜 AWS가 아니라 GCP인가: AWS 신규 프리티어 $200 크레딧이 "기존/이전 계정과 정보가 겹치면 미적용"으로 막혔다. GCP는 신규 가입 $300 크레딧(90일)을 준다. 추가로 Vertex 임베딩을 768차원으로 맞출 수 있어 현재 pgvector 스키마(768)를 그대로 쓴다(AWS Titan은 1024 강제라 재인덱싱 부담이 더 컸다).

---

## 0. 결정 요약 (확정)

| 항목 | 결정 | 이유 |
|---|---|---|
| 생성/비전 모델 | Gemini 2.5 Flash (`gemini-2.5-flash`) | 텍스트 생성과 비전을 한 모델로 커버(llava 대체 포함), 저지연/저비용 |
| 임베딩 모델 | `gemini-embedding-001`, 출력 768차원 | MRL로 768 절단 가능 → 현재 차원 유지, 다국어(한+영) 대응 |
| 임베딩 차원 | 768 (변경 없음) | 현재 granite-embedding과 동일 차원 → pgvector/avro 스키마 그대로 |
| 컴퓨트 | Compute Engine e2-medium + docker-compose | LLM을 밖으로 뺐으니 앱+pgvector만 → 작은 VM으로 충분 |
| 인증 | VM에 서비스 계정 연결(ADC) | 키 파일을 디스크에 두지 않는다(키 유출 방지) |
| 리전 | asia-northeast3(서울) 우선, 미가용 시 us-central1 | 데모 지연 최소화 |
| 비용 | $300 크레딧 / 90일 | 3개월 무료. 이후 종량제(월 $25~30) 또는 중단 |

**핵심 주의 — 90일 한도**: $300은 90일 안에 써야 하고 기간이 지나면 남은 크레딧도 소멸한다. "6개월 상시"가 목표라면 90일 후엔 종량제로 전환하거나 잠깐 내려야 한다. 취업 데모 창구로는 3개월 무료로 시작해 상황 보고 결정한다.

---

## 1. 아키텍처 변화 (before → after)

```
[before] 한 박스(16GB+)
  Spring 앱 ── HTTP ──> Ollama(granite4 / llava / granite-embedding:278m, 768-dim)
                         + pgvector + parquet

[after] 작은 VM(e2-medium) + 관리형 추론
  Spring 앱 ── Vertex AI SDK ──> Gemini 2.5 Flash(생성+비전), gemini-embedding-001(768-dim)
   └ pgvector(768-dim, 변경 없음) + parquet     (서비스 계정/ADC로 인증, 키 파일 없음)
```

변화 세 가지: (1) 추론 대상이 Ollama HTTP에서 Vertex SDK로, (2) 임베딩 모델은 바뀌지만 **차원은 768 유지**라 스키마 변경 없음(재임베딩만), (3) VM에서 Ollama 컨테이너 제거.

---

## 2. 단계별 절차

### Step 1 — GCP 프로젝트 + 크레딧 + API 활성화

**왜**: Vertex AI는 프로젝트 단위로 API를 켜야 호출된다. 크레딧 적용 여부도 가입 시 확정된다.

**작업**: 신규 가입 시 $300 크레딧이 표시되는지 확인 → 프로젝트 생성 → Vertex AI API(`aiplatform.googleapis.com`) 활성화 → 결제계정 연결.

**검증**:
```bash
gcloud services list --enabled --filter="aiplatform"   # aiplatform.googleapis.com 보이면 통과
gcloud billing accounts list                            # 크레딧 연결된 결제계정 확인
```
크레딧이 안 붙으면(이전 계정 이력) 비용 전제가 바뀌니 여기서 멈추고 재검토.

### Step 2 — 서비스 계정 + 권한 (ADC)

**왜**: 키 JSON 파일을 VM이나 리포에 두면 유출 시 그대로 과금으로 이어진다. VM에 서비스 계정을 붙이면 Application Default Credentials로 키 파일 없이 인증된다. 면접에서도 "키 파일 안 씀"은 기본 점수다.

**작업**: 서비스 계정 생성 → `roles/aiplatform.user` 부여 → Compute Engine VM 생성 시 그 서비스 계정 연결.

**검증**: VM에서
```bash
gcloud auth application-default print-access-token >/dev/null && echo OK   # 토큰 발급되면 통과
```

### Step 3 — 추론 클라이언트 교체 (생성 + 비전)

**왜**: 현재 `application.yaml`의 `ollama.*`를 직접 호출하는 커스텀 HTTP 클라이언트를 쓴다. Vertex로 가려면 같은 자리에 Vertex 호출부를 둔다. 기존에 직접 클라이언트를 짠 구조라, Google Cloud Vertex AI Java SDK로 기존 Ollama 클라이언트를 미러링하는 게 변경 표면이 가장 작다.

**작업**:
- `pom.xml`에 `google-cloud-vertexai` 추가
- 기존 Ollama 클라이언트와 같은 인터페이스로 `VertexChatClient` 구현 (`generateContent`)
- 비전: llava 분기 호출을 Gemini 멀티모달 image part로 대체
- 설정 키 신설: `vertex.project-id`, `vertex.location`, `vertex.chat-model`, `vertex.chat-models`(UI 드롭다운용), `vertex.embed-model`
- `application-prod.yaml`에서 추론 대상 전환, `OLLAMA_URL` 의존 제거

**검증**:
```bash
curl -s -X POST localhost:8080/api/rag/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"한 줄 자기소개"}' | jq .

curl -s -X POST localhost:8080/api/multimodal/ask \
  -F 'image=@sample.png' -F 'question="이 이미지 설명"'
```
두 응답이 정상 텍스트면 통과. `/api/rag/models`에 Vertex 모델 목록이 떠야 UI 드롭다운도 맞는다.

### Step 4 — 임베딩 교체 + 재임베딩 (차원은 유지)

**왜**: 임베딩 모델이 granite-embedding에서 `gemini-embedding-001`로 바뀌면 벡터 공간이 달라져 **재임베딩이 강제**된다. 다만 출력 차원을 768로 맞추면 pgvector 컬럼(`vector(768)`)과 `article.avsc`는 그대로 둘 수 있다. AWS Titan(1024)과 달리 스키마 변경이 없는 게 GCP를 택한 실익 중 하나.

**작업**:
1. 임베딩 호출을 `gemini-embedding-001`(`output_dimensionality=768`)로 교체
2. pgvector 컬럼 차원은 **유지(768)**. 단 기존 벡터 값은 무효이므로 전체 재임베딩 필요
3. UI 표기 수정: `index.html`의 "Ollama" 라벨, `app.js`의 모델 라벨 접두사 (차원 "768"은 그대로 유효)
4. 코퍼스 전체 재적재 → 재임베딩 → 재색인

**검증**:
```bash
curl -s localhost:8080/api/data/index/stats | jq .   # dim=768, count>0
python3 eval/run_eval.py                              # recall 회귀 확인
```
[EVALUATION.md](EVALUATION.md)의 golden set 기준 recall이 교체 전과 비슷하면 통과. 특히 한국어 케이스 recall을 본다([EMBEDDINGS.md](EMBEDDINGS.md)의 한국어 11/11 기준). 떨어지면 task type 설정(`RETRIEVAL_DOCUMENT`/`RETRIEVAL_QUERY`)이나 차원(512 vs 768)을 재검토. "측정 없이 최적화 없다"를 그대로 적용한다.

### Step 5 — Compute Engine VM 프로비저닝

**왜**: LLM을 뺐으니 앱+pgvector만 돌면 된다. e2-medium(2 vCPU / 4GB)이면 충분하되 JVM 힙과 Postgres가 같이 떠서 메모리가 빠듯하니 힙을 묶는다.

**작업**:
- e2-medium, Debian/Ubuntu, 디스크 30GB
- Step 2의 서비스 계정 연결, cloud-platform 스코프 허용
- Docker + compose 설치
- 방화벽: 22(내 IP만), 80/443(공개). **5432(pg), 11434(구 ollama)는 외부 비노출**
- JVM 힙 상한(예: `-Xmx1536m`)으로 OOM 방지

**검증**: `docker --version`, `free -m`, 방화벽 규칙에 5432가 안 열려 있는지 콘솔에서 확인.

### Step 6 — 배포 (compose에서 Ollama 제거)

**왜**: 추론을 Vertex로 뺐으므로 `docker-compose.prod.yml`의 `ollama`, `ollama-init`와 `OLLAMA_URL`은 불필요. 남기면 수 GB 모델을 헛으로 받는다.

**작업**:
- compose에서 `ollama`, `ollama-init` 삭제, `app`의 `OLLAMA_URL`/의존성 제거
- `app`에 `VERTEX_PROJECT_ID`, `VERTEX_LOCATION` 환경변수 추가, 인증은 ADC(서비스 계정)에 위임
- `.env` 작성(`.env.example` 복사 → `DB_PASSWORD` 등). **시크릿은 커밋 금지**
- `docker compose -f docker-compose.prod.yml up -d`

**검증**:
```bash
docker compose -f docker-compose.prod.yml ps     # postgres, app 만 Up
docker compose logs app | grep -i vertex         # Vertex 클라이언트 초기화 로그
curl -s localhost:8080/actuator/health           # UP
```

### Step 7 — HTTPS + 인증 활성화

**왜**: 공개 URL을 무인증/HTTP로 열면 누구나 호출해 크레딧을 태운다. 데모라도 최소선은 지킨다.

**작업**:
- Caddy 또는 Nginx로 80/443 리버스 프록시, Let's Encrypt 자동 인증서
- `SECURITY_ENABLED=true`, `SECURITY_MODE=apikey-filter` (compose 기본이 데모용 false라 운영 전환 필요)
- 앱 포트 8080은 프록시 뒤로만

**검증**: `https://<도메인>`이 유효 인증서로 열리고, API 키 없이 호출 시 401이 나오는지 확인.

### Step 8 — 비용 가드

**왜**: 90일이 지나거나 $300을 다 쓰면 종량제로 과금된다. 모르고 방치하면 청구된다.

**작업**: Billing → Budgets & alerts에서 월 한도(예: $30) 알림 생성. 크레딧 잔액/소진 예상일 확인. 데모 종료일 또는 90일 시점에 VM 중지/삭제 리마인더.

**검증**: 예산 알림 규칙이 활성인지, Billing 리포트에서 Vertex/Compute 일일 비용이 예상 범위인지 확인.

---

## 3. 전체 스모크 체크리스트 (배포 후 한 번에)

- [ ] `gcloud auth application-default print-access-token` → 서비스 계정으로 인증
- [ ] `/api/rag/ask` 텍스트 응답 정상
- [ ] `/api/multimodal/ask` 이미지 질의 정상 (Gemini 비전)
- [ ] `/api/data/index/stats` → dim=768, count>0
- [ ] `python3 eval/run_eval.py` → recall 회귀 없음 (한국어 케이스 포함)
- [ ] `/api/governance/logs` → 호출이 감사 로그에 남음
- [ ] HTTPS 유효 + 무인증 호출 401
- [ ] 방화벽에 5432/11434 외부 비노출
- [ ] Budgets 알림 + 크레딧 잔액 확인

---

## 4. 보안 / 데이터 / 거버넌스

- 인증: VM 서비스 계정(ADC)으로 Vertex 호출, 키 파일 없음. 최소 권한(`roles/aiplatform.user`).
- 시크릿: `.env`만, 리포엔 `.env.example`만.
- 데이터 경계: 질의/문서 일부가 Vertex로 나간다(GCP 내부). 데모 코퍼스는 공개 가능한 샘플로 한정하고 민감 데이터는 올리지 않는다. 근거/판단은 [DEEP-DIVE-DATA-GOVERNANCE.md](DEEP-DIVE-DATA-GOVERNANCE.md).
- 감사: 기존 governance 로그로 "어떤 질의가 어떤 응답을 받았는지" 추적 가능. Phase 3에서 모델/설정 메타까지 묶는다.
- 파기: 데모 종료 또는 90일 시점에 VM/디스크/데이터 삭제 절차를 미리 정의.

---

## 5. 비용 추정 (2026-06 기준, 데모 트래픽)

| 항목 | 대략 비용 |
|---|---|
| Compute Engine e2-medium | 약 $25/월 |
| 디스크 30GB | 약 $1.2/월 |
| Vertex 토큰(Gemini Flash + 임베딩, 데모 수준) | 수 달러/월 |
| 합계 | 월 $28~32 → $300 크레딧으로 90일 충분(잔액 여유) |

90일 경과 후 계속 띄우려면 월 $28~32 종량제. 요금은 변동되므로 기동 직전 콘솔에서 재확인.

---

## 6. 다음 단계 (Phase 1~3 로드맵)

Phase 0가 끝나면 동작하는 서비스가 생긴다. 그 위에 MLOps 신뢰도를 얹는다.

- **Phase 1 CI/CD**: `.github/workflows`로 빌드 → 테스트 → eval 게이트(recall 임계 미달 시 배포 차단) → ghcr 푸시 → VM 배포 자동화.
- **Phase 2 운영 모니터링**: 지연, 토큰 비용, 검색 품질(recall) 추적. governance 로그를 시계열로 보고 드리프트 감지.
- **Phase 3 모델 버전 레지스트리**: 어떤 모델/설정 조합으로 어떤 응답이 나갔는지 추적. 응답에 모델 버전과 설정 해시를 함께 기록.

각 Phase는 별도 문서로 같은 형식(왜/작업/검증)으로 작성한다.

---

## 7. 출처

- GCP 무료 체험 $300 / 90일: https://cloud.google.com/free/docs/free-cloud-features
- Vertex 임베딩 차원(text-embedding-005 / gemini-embedding-001): https://docs.cloud.google.com/vertex-ai/generative-ai/docs/embeddings/get-text-embeddings
- Gemini 2.5 Flash (Vertex, 멀티모달): https://cloud.google.com/blog/products/ai-machine-learning/gemini-2-5-pro-flash-on-vertex-ai
- Vertex 모델 목록: https://docs.cloud.google.com/vertex-ai/generative-ai/docs/models

요금/모델 가용성은 시점에 따라 바뀌므로 기동 직전 각 공식 콘솔에서 재확인.
