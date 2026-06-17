# Cloud Deployment — 플랫폼 선택 의사결정

배포할 클라우드를 "왜 / 어디에 / 어떻게"로 고른 기록. 로컬/컨테이너 셋업 절차는 [DEPLOYMENT.md](DEPLOYMENT.md), 운영 관점은 [OPERATIONS.md](OPERATIONS.md), 보안은 [SECURITY.md](SECURITY.md)를 참고.

조건(2026-06 기준):
- 용도: 취업용 포트폴리오 데모. 면접관이 링크로 들어와 동작을 확인.
- 기간: 한시 운영 3~6개월. "평생 무료" 불필요.
- 목표: 비용 최소 + 데모 기간 동안 안정적으로 접근 가능.

---

## 1. 결론부터

| 우선순위 | 선택 | 한 줄 이유 |
|---|---|---|
| 1순위 (자체호스팅 유지, 최저비용) | Hetzner 정액 VPS (CPX41, 8 vCPU / 16GB) | 6개월 총액 약 $110, x86이라 ARM 멀티아치 고민 없음, Ollama CPU 추론에 RAM 충분 |
| 2순위 (AWS 이력 + IBM 테마) | AWS + LLM 분리(watsonx.ai) | 앱+pgvector만 작은 박스로 → 신규 프리티어 $200/6개월 안에 들어옴 |
| 조건부 (풀스택 AWS) | AWS, 보여줄 때만 start/stop | 24/7은 크레딧 못 버팀. 데모 시점에만 기동하는 운영 전제일 때만 성립 |

취업용 결정: **자체호스팅 그대로면 Hetzner, AWS 경험을 어필하려면 경로 C(아래)**. 둘 중 어느 쪽이든 "왜 골랐는지"를 면접에서 설명할 수 있는 게 핵심이라 본 문서가 그 근거다.

---

## 2. 무엇이 클라우드를 결정하는가 — 스택 자원 프로파일

한 박스에 동시에 떠야 하는 것(`docker-compose.prod.yml` 기준):

| 컴포넌트 | 자원 부담 | 비고 |
|---|---|---|
| Ollama + `ibm/granite4` + `granite-embedding:278m` + `llava` | 모델 합계 대략 10~16GB, CPU 추론 시 느림 | llava(비전)가 특히 무거움. GPU 없으면 응답 지연 |
| PostgreSQL + pgvector | 중간 (JPA 감사/카탈로그 + 벡터 겸용) | DB 한 개가 두 역할 |
| Spring Boot 앱 (Semeru JRE) | JVM 힙 + parquet 콜드스토어 | Semeru/OpenJ9 기동 옵션 주의 |

핵심 제약: **Ollama 때문에 16GB급 RAM이 필요**하다. "작은 무료 인스턴스 하나"로는 안 된다. 그래서 진짜 갈림길은 플랫폼이 아니라 **LLM을 박스 안에서 직접 돌리느냐, 외부 추론으로 빼느냐**다.

---

## 3. 핵심 갈림길 — 자체호스팅 vs LLM 분리

| 구분 | 경로 A/B (Ollama 자체호스팅) | 경로 C (LLM 외부 추론) |
|---|---|---|
| 박스 RAM | 16GB급 필요 | 2~4GB로 충분 (앱+pgvector만) |
| 월 비용 | 큰 박스 = 비쌈 | 작은 박스 + API 종량 |
| 데이터 이동 | 추론이 내부에서 끝남 (외부 유출 0) | 질의/문서가 외부 API로 나감 → 거버넌스 검토 필요 |
| 코드 변경 | 없음 (지금 그대로) | Ollama 클라이언트 → watsonx/Bedrock 클라이언트 교체 |
| IBM 테마 | granite 로컬 | granite을 watsonx.ai 호스팅으로 (테마 강화) |

이 결정이 다음 작업(멀티아치 이미지 빌드 vs 추론 클라이언트 교체)을 가른다. 먼저 정하고 진행한다.

---

## 4. 플랫폼별 비교 (2026-06-17 기준 요금)

요금은 변동된다. 아래는 의사결정 시점 스냅샷이며, 실제 기동 전 각 콘솔에서 재확인할 것.

### 4.1 AWS — 기간은 맞지만 함정은 RAM

2025-07-15부터 신규 계정 프리티어가 크레딧 모델로 바뀜: 가입 $100 + 온보딩 과제로 최대 $100 = **$200, 6개월 유효**. "6개월 데모"라는 기간 자체는 정확히 맞는다.

문제는 16GB 박스를 그 크레딧으로 6개월 24/7 돌릴 수 없다는 것:

| 방식 | 인스턴스 | 대략 비용 | $200로 버티는 기간 |
|---|---|---|---|
| Ollama 그대로 24/7 | t3.xlarge (4 vCPU / 16GB) | 약 $120/월 | 6~7주면 소진 (불가) |
| Ollama, 하루 8시간만 기동 | t3.xlarge, start/stop | 약 $40/월 | 약 5개월 (항상 켜짐 포기) |
| LLM 분리(경로 C) | t3.medium + pgvector | 약 $30/월 | 6개월 충분 |

결론: AWS는 **LLM을 분리하거나(경로 C), 보여줄 때만 켜는 운영을 받아들일 때만** 6개월 데모에 들어맞는다. 풀스택을 24/7로 올리는 용도로는 부적합.

### 4.2 Oracle Cloud Always Free — 2026-06-15부터 약화

이전엔 Ampere A1 4 OCPU / 24GB가 평생 무료라 자체호스팅 무료 데모의 최강 카드였으나, **2026-06-15부터 무료 한도가 2 OCPU / 12GB로 축소**됐다. 12GB는 granite4+llava+embedding을 동시에 띄우기엔 빠듯하다(모델을 줄이면 가능). ARM이라 멀티아치 이미지도 필요. 한시 데모엔 매력이 떨어졌다.

### 4.3 Hetzner 정액 VPS — 자체호스팅 한시 데모의 가성비 1위

CPX41: 8 vCPU / 16GB / 240GB(x86). 약 €16/월 → **6개월 총액 약 $110**. 정액이라 과금 폭탄 없음, x86이라 현재 `Dockerfile` 그대로 빌드, RAM 넉넉. Contabo는 더 싸지만 오버셀로 성능 변동이 크다.

"공짜처럼 보이는 AWS $200"보다, 16GB를 6개월 쓰는 실비로는 정액 VPS가 압도적으로 싸다. AWS의 가치는 무료가 아니라 "AWS를 다뤄봤다"는 이력에 있다.

---

## 5. 보안 / 데이터 / 거버넌스 체크리스트

데모라도 공개 URL이면 최소선은 지킨다. 면접에서 가점 요소이기도 하다.

- 시크릿: `DB_PASSWORD` 등은 `.env`(커밋 금지)로만. 리포에 평문 금지. `.env.example`만 커밋.
- 인증: `docker-compose.prod.yml`의 `SECURITY_ENABLED`를 공개 배포 시 `true`로. 데모용 무인증(`false`)으로 공개 URL에 올리지 말 것.
- 네트워크: 8080을 0.0.0.0에 그대로 노출하지 말고, 리버스 프록시(Caddy/Nginx) + HTTPS(Let's Encrypt). Postgres/Ollama 포트는 외부 비노출(컨테이너 네트워크 내부만).
- 데이터 경계(경로 C 선택 시): 질의와 인덱싱 문서가 외부 추론 API로 나간다. 데모 데이터를 공개 가능한 샘플로 한정하고, 민감 코퍼스는 올리지 않는다. 이 결정과 근거는 [DEEP-DIVE-DATA-GOVERNANCE.md](DEEP-DIVE-DATA-GOVERNANCE.md)에 연결.
- 감사: pgvector와 겸하는 JPA 감사 로그가 남으므로, 데모 후 데이터 파기 절차를 정해둔다.
- 비용 가드: AWS면 Budgets 알림(온보딩 과제에도 포함), 정액 VPS면 해당 없음. 데모 종료일에 인스턴스 파기 리마인더.

---

## 6. 다음 스텝 (선택에 따라 분기)

선결정: **Ollama를 박스 안에 둘 것인가(A/B) vs LLM을 watsonx/Bedrock으로 뺄 것인가(C).**

자체호스팅(Hetzner 등)로 가면:
1. 서버 프로비저닝 + Docker 설치
2. 리포 클론, `.env` 작성(`.env.example` 복사 → 시크릿 채움)
3. `docker compose -f docker-compose.prod.yml up -d` (첫 기동 시 ollama-init가 모델 수 GB pull → 받는 동안 ask 대기)
4. Caddy/Nginx로 HTTPS 리버스 프록시, `SECURITY_ENABLED=true`
5. 헬스체크/로그 확인, 데모 시나리오 리허설

경로 C(LLM 분리)로 가면:
1. Ollama 클라이언트를 watsonx.ai(또는 Bedrock) 추론 클라이언트로 교체
2. 임베딩/생성 모델 매핑 정리(granite → watsonx granite)
3. 작은 박스(앱+pgvector)로 축소 배포 + 위 4~5 동일
4. 데이터 경계(5절) 재검토

---

## 부록 — 요금 출처

- AWS 신규 프리티어 $200 크레딧 / 6개월: https://aws.amazon.com/about-aws/whats-new/2025/07/aws-free-tier-credits-month-free-plan/
- Oracle Cloud Always Free Ampere A1 (2026-06 변경): https://medium.com/@imvinojanv/setup-always-free-vps-with-4-ocpu-24gb-ram-and-200gb-storage-the-ultimate-oracle-cloud-guide-bed5cbf73d34

요금은 시점에 따라 바뀌므로 기동 직전 각 공식 콘솔에서 재확인.
