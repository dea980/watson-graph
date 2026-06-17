> 상태: 미채택(검토 기록). 벤더 중립 방식으로 전환됨 → [PHASE0-DEPLOYMENT.md](PHASE0-DEPLOYMENT.md) 참고. AWS는 신규 프리티어 자격 미적용으로 보류. 이 문서는 비교 근거로 남겨둔다.

# Phase 0 — AWS + Bedrock 배포 가이드 (미채택)

LLM을 Ollama 자체호스팅에서 AWS Bedrock 관리형 추론으로 옮기고, 앱과 pgvector를 EC2에 올리는 단계별 절차. 각 단계에 **왜 필요한지 / 작업 / 검증**을 붙였다. 코드는 직접 작성하고, 이 문서는 순서와 검증 기준을 잡는 용도다.

상위 맥락(클라우드 선택 근거)은 [CLOUD-DEPLOYMENT.md](CLOUD-DEPLOYMENT.md), 로컬 셋업은 [DEPLOYMENT.md](DEPLOYMENT.md), 임베딩 근거는 [EMBEDDINGS.md](EMBEDDINGS.md), 보안은 [SECURITY.md](SECURITY.md).

---

## 0. 결정 요약 (확정)

| 항목 | 결정 | 이유 |
|---|---|---|
| 생성/비전 모델 | Claude Haiku (Bedrock Converse) | 텍스트 생성과 비전을 한 모델로 커버(llava 대체 포함), 토큰비 저렴, Bedrock 기본 경로 |
| 임베딩 모델 | Amazon Titan Text Embeddings V2 (`amazon.titan-embed-text-v2:0`) | AWS 한 생태계로 통일, 저렴, 1024/512/256 차원 선택 가능 |
| 임베딩 차원 | 1024 | 기본값이자 최고 품질. 현재 768에서 변경되므로 재인덱싱 필요 |
| 컴퓨트 | EC2 t3.medium + docker-compose | LLM을 밖으로 뺐으니 앱+pgvector만 → 작은 박스로 충분, $200 크레딧 6개월 안 |
| 인증 | EC2 인스턴스 IAM 역할 | 액세스 키를 코드/`.env`에 박지 않는다(키 유출 방지) |
| 리전 | ap-northeast-2(서울) 우선, 모델 미가용 시 us-east-1 | 데모 지연 최소화. 단 최신 모델은 cross-region inference profile 필요할 수 있음 |

**왜 Bedrock인가**: AWS 신규 프리티어 $200 크레딧은 16GB Ollama 박스를 6개월 24/7로 못 버틴다(6~7주면 소진). LLM을 관리형으로 빼면 박스가 2~4GB로 줄어 $200 안에 6개월이 들어온다. 동시에 "관리형 Inference 연동"은 채용 JD의 MLOps 항목(실 서비스 배포를 고려한 Inference API)과 직접 맞닿는다.

---

## 1. 아키텍처 변화 (before → after)

```
[before] 한 박스(16GB+)
  Spring 앱 ── HTTP ──> Ollama(granite4 / llava / granite-embedding:278m, 768-dim)
                         + pgvector + parquet

[after] 작은 박스(t3.medium) + 관리형 추론
  Spring 앱 ── AWS SDK(Converse/InvokeModel) ──> Bedrock(Claude Haiku, Titan v2 1024-dim)
   └ pgvector(1024-dim) + parquet         (IAM 역할로 인증, 키 없음)
```

핵심 변화 세 가지: (1) 추론 호출 대상이 Ollama HTTP에서 Bedrock SDK로 바뀜, (2) 임베딩 차원 768 → 1024이라 벡터 저장소를 재구축, (3) 박스에서 Ollama 컨테이너가 사라짐.

---

## 2. 단계별 절차

### Step 1 — Bedrock 모델 액세스 활성화

**왜**: Bedrock은 계정/리전별로 모델을 명시적으로 켜야 호출된다. 안 켜면 `AccessDeniedException`이 난다.

**작업**: AWS 콘솔 → Bedrock → Model access → Claude Haiku, Titan Text Embeddings V2 두 개 요청/활성화. 사용할 리전(서울 또는 us-east-1)에서 켤 것.

**검증**:
```bash
aws bedrock list-foundation-models --region ap-northeast-2 \
  --query "modelSummaries[?contains(modelId,'titan-embed-text-v2') || contains(modelId,'claude-3-5-haiku')].modelId"
```
대상 모델 ID가 목록에 나오면 통과. 서울에서 Claude가 안 보이면 us-east-1로 바꾸거나 cross-region inference profile ID를 쓴다.

### Step 2 — IAM 권한 (인스턴스 역할)

**왜**: 액세스 키를 `.env`나 코드에 넣으면 유출 시 그대로 과금 폭탄으로 이어진다. EC2 인스턴스 역할로 주면 키가 디스크에 존재하지 않는다. 면접에서도 "키 하드코딩 안 함"은 기본 점수다.

**작업**: 최소 권한 정책을 만든 역할을 EC2에 부여.
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["bedrock:InvokeModel", "bedrock:Converse"],
    "Resource": "*"
  }]
}
```
(운영이면 `Resource`를 특정 모델 ARN으로 좁힌다.)

**검증**: 인스턴스에서 자격증명이 역할로 잡히는지 확인.
```bash
aws sts get-caller-identity        # Arn에 assumed-role/... 가 보여야 함
```

### Step 3 — 추론 클라이언트 교체 (생성 + 비전)

**왜**: 현재 `application.yaml`의 `ollama.*`를 직접 호출하는 커스텀 HTTP 클라이언트를 쓴다. Bedrock으로 가려면 같은 자리에 Bedrock 호출부를 둔다. 기존에 직접 클라이언트를 짠 구조라, 프레임워크를 새로 얹기보다 **AWS SDK `bedrock-runtime`의 Converse API**로 기존 Ollama 클라이언트를 미러링하는 게 변경 표면이 가장 작다. (Spring AI `spring-ai-starter-model-bedrock-converse`를 도입하는 대안도 있으나, 지금 코드엔 Spring AI 의존성이 없어 신규 도입 비용이 있다.)

**작업**:
- `pom.xml`에 AWS SDK `bedrock-runtime` 추가
- 기존 Ollama 클라이언트와 같은 인터페이스로 `BedrockChatClient` 구현 (Converse 호출)
- 비전: llava 분기 호출을 Claude Converse의 image 블록으로 대체
- 설정 키 신설: `bedrock.region`, `bedrock.chat-model`, `bedrock.chat-models`(UI 드롭다운용), `bedrock.embed-model`
- `application-prod.yaml`에서 추론 대상 전환, `OLLAMA_URL` 의존 제거

**검증**: 앱 단독으로 생성/비전 스모크.
```bash
curl -s -X POST localhost:8080/api/rag/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"한 줄 자기소개"}' | jq .

curl -s -X POST localhost:8080/api/multimodal/ask \
  -F 'image=@sample.png' -F 'question="이 이미지 설명"'
```
두 응답이 정상 텍스트면 통과. `/api/rag/models`에 Bedrock 모델 목록이 떠야 UI 드롭다운도 맞는다.

### Step 4 — 임베딩 교체 + pgvector 재인덱싱 (숨은 핵심 작업)

**왜**: 지금 임베딩은 granite-embedding 278m의 **768차원**이다(`index.html`, `article.avsc` 기준). Titan v2는 256/512/1024만 지원해 768을 그대로 못 쓴다. 차원이 바뀌면 기존 벡터는 전부 무효라 **재인덱싱이 강제**된다. 이 단계를 빼먹으면 차원 불일치로 검색이 깨진다.

**작업**:
1. 임베딩 호출을 Titan(`amazon.titan-embed-text-v2:0`, `dimensions=1024`)로 교체
2. pgvector 컬럼 차원을 `vector(1024)`로 변경 (기존 테이블/인덱스 drop 후 재생성)
3. parquet 콜드스토어의 `article.avsc` embedding 차원 가정/문서 표기(768) 갱신
4. UI 표기 수정: `index.html`의 "768-dim embeddings", "Ollama"와 `app.js`의 모델 라벨 접두사
5. 코퍼스 전체 재적재 → 재임베딩 → 재색인

**검증**: 인덱스 통계와 평가 하니스로 회귀 확인.
```bash
curl -s localhost:8080/api/data/index/stats | jq .   # dim=1024, count>0 확인
python3 eval/run_eval.py                              # recall이 이전 수준 유지되는지
```
[EVALUATION.md](EVALUATION.md)의 golden set 기준 recall이 교체 전과 비슷하면 통과. 떨어지면 차원(512 vs 1024)이나 정규화 옵션을 재검토. "측정 없이 최적화 없다"를 여기서 그대로 적용한다.

### Step 5 — EC2 프로비저닝

**왜**: LLM을 뺐으니 앱+pgvector만 돌면 된다. t3.medium(2 vCPU / 4GB)면 충분하되 JVM 힙과 Postgres가 같이 떠서 메모리가 빠듯하니 힙을 묶는다.

**작업**:
- t3.medium, Amazon Linux 2023, gp3 30GB
- Docker + compose 설치
- 보안그룹: 22(내 IP만), 80/443(공개). **5432(pg), 11434(구 ollama)는 외부 비노출**
- JVM 힙 상한 설정(예: `-Xmx1536m`)으로 OOM 방지

**검증**: `docker --version`, `free -m`(가용 메모리), 보안그룹에 5432가 안 열려 있는지 콘솔에서 확인.

### Step 6 — 배포 (compose에서 Ollama 제거)

**왜**: 추론을 Bedrock으로 뺐으므로 `docker-compose.prod.yml`의 `ollama`, `ollama-init` 서비스와 `OLLAMA_URL`은 더 이상 필요 없다. 남기면 수 GB 모델을 헛으로 받는다.

**작업**:
- compose에서 `ollama`, `ollama-init` 삭제, `app`의 `OLLAMA_URL`/의존성 제거
- `app`에 `AWS_REGION`(또는 `bedrock.region`) 환경변수 추가, 인증은 인스턴스 역할에 위임
- `.env` 작성(`.env.example` 복사 → `DB_PASSWORD` 등). **시크릿은 커밋 금지**
- `docker compose -f docker-compose.prod.yml up -d`

**검증**:
```bash
docker compose -f docker-compose.prod.yml ps     # postgres, app 만 Up
docker compose logs app | grep -i bedrock        # Bedrock 클라이언트 초기화 로그
curl -s localhost:8080/actuator/health           # UP (또는 /api 헬스 엔드포인트)
```

### Step 7 — HTTPS + 인증 활성화

**왜**: 공개 URL을 무인증/HTTP로 열면 누구나 호출해 토큰비를 태운다. 데모라도 최소선은 지킨다.

**작업**:
- Caddy 또는 Nginx로 80/443 리버스 프록시, Let's Encrypt 자동 인증서
- `SECURITY_ENABLED=true`, `SECURITY_MODE=apikey-filter` (compose 기본이 데모용 false라 운영 전환 필요)
- 앱 포트 8080은 프록시 뒤로만

**검증**: `https://<도메인>`이 유효 인증서로 열리고, API 키 없이 호출 시 401이 나오는지 확인.

### Step 8 — 비용 가드

**왜**: 크레딧 소진이나 6개월 경과 후엔 과금이 시작된다. 모르고 방치하면 청구된다.

**작업**: AWS Budgets에서 월 한도(예: $30) 알림 생성. (신규 프리티어 온보딩 과제에도 포함돼 크레딧도 적립된다.) 데모 종료일에 인스턴스 파기 리마인더.

**검증**: Budgets에 알림 규칙이 활성인지, Cost Explorer에서 Bedrock/EC2 일일 비용이 예상 범위인지 확인.

---

## 3. 전체 스모크 체크리스트 (배포 후 한 번에)

- [ ] `aws sts get-caller-identity` → 인스턴스 역할로 인증
- [ ] `/api/rag/ask` 텍스트 응답 정상
- [ ] `/api/multimodal/ask` 이미지 질의 정상 (Claude 비전)
- [ ] `/api/data/index/stats` → dim=1024, count>0
- [ ] `python3 eval/run_eval.py` → recall 회귀 없음
- [ ] `/api/governance/logs` → 호출이 감사 로그에 남음
- [ ] HTTPS 유효 + 무인증 호출 401
- [ ] 보안그룹에 5432/11434 외부 비노출
- [ ] Budgets 알림 활성

---

## 4. 보안 / 데이터 / 거버넌스

- 인증: 인스턴스 IAM 역할로 Bedrock 호출, 정적 키 없음. 최소 권한 정책.
- 시크릿: `.env`만, 리포엔 `.env.example`만.
- 데이터 경계: 질의/문서 일부가 Bedrock으로 나간다(AWS 내부). Bedrock은 기본적으로 입력을 모델 학습에 쓰지 않으나, 데모 코퍼스는 공개 가능한 샘플로 한정하고 민감 데이터는 올리지 않는다. 근거/판단은 [DEEP-DIVE-DATA-GOVERNANCE.md](DEEP-DIVE-DATA-GOVERNANCE.md).
- 감사: 기존 governance 로그로 "어떤 질의가 어떤 응답을 받았는지" 추적 가능. Phase 3(모델 버전 레지스트리)에서 모델/설정 메타까지 묶는다.
- 파기: 데모 종료 시 인스턴스/볼륨/데이터 삭제 절차를 미리 정의.

---

## 5. 비용 추정 (2026-06 기준, 데모 트래픽)

| 항목 | 대략 비용 |
|---|---|
| EC2 t3.medium | 약 $30/월 |
| EBS gp3 30GB | 약 $2.4/월 |
| Bedrock 토큰(Claude Haiku + Titan, 데모 수준) | 수 달러/월 |
| 합계 | 월 $35~40 → $200 크레딧으로 약 6개월 |

요금은 변동되므로 기동 직전 콘솔에서 재확인.

---

## 6. 다음 단계 (Phase 1~3 로드맵)

Phase 0가 끝나면 동작하는 서비스가 생긴다. 그 위에 MLOps 신뢰도를 얹는다.

- **Phase 1 CI/CD**: `.github/workflows`로 빌드 → 테스트 → eval 게이트(recall 임계 미달 시 배포 차단) → ghcr 푸시 → EC2 배포 자동화. 지금 compose 주석엔 CI 언급이 있으나 워크플로 파일이 비어 있다.
- **Phase 2 운영 모니터링**: 지연, 토큰 비용, 검색 품질(recall) 추적. governance 로그를 시계열로 보고 드리프트를 감지.
- **Phase 3 모델 버전 레지스트리**: 어떤 모델/설정 조합으로 어떤 응답이 나갔는지 추적. 응답에 모델 버전과 설정 해시를 함께 기록.

각 Phase는 별도 문서로 같은 형식(왜/작업/검증)으로 작성한다.

---

## 7. 출처

- Bedrock Converse API (Spring AI 참고): https://docs.spring.io/spring-ai/reference/api/chat/bedrock-converse.html
- Titan Text Embeddings V2 (모델 ID/차원): https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-amazon-titan-text-embeddings-v2.html
- Claude on Amazon Bedrock (리전/모델): https://aws.amazon.com/bedrock/anthropic/
- AWS 신규 프리티어 $200 / 6개월: https://aws.amazon.com/about-aws/whats-new/2025/07/aws-free-tier-credits-month-free-plan/

요금/모델 가용성은 시점에 따라 바뀌므로 기동 직전 각 공식 콘솔에서 재확인.
