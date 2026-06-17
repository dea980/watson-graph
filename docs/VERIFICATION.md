# Verification Guide

MiniWatson의 각 기능을 어떻게 테스트하고 검증했는지 모은 가이드. "이게 동작한다는 걸 어떻게 아느냐"에 대한 답이다.

검증은 4계층으로 나뉜다.

1. 단위 테스트 (JUnit) — 순수 로직, 결정적, CI 가능 (TESTING.md)
2. 오프라인 평가 (eval harness) — retrieval 품질, 정답셋 기반 (EVALUATION.md)
3. 수동 API 검증 (curl) — 엔드투엔드 동작, 통합 확인
4. UI 확인 — 사람이 화면에서 최종 확인

아래는 기능별로 어느 계층으로 검증했는지와 구체적 방법이다.

---

## 1. 검색 / RAG

### 청킹 (Chunking)
- 단위: RecursiveChunkerTest — 짧은 글=1청크, 긴 글 분할, 모든 청크 <= maxSize, 빈 청크 없음
- 수동: 파일 업로드 후 `GET /api/data/documents`로 청크 수 확인. recursive/fixed/semantic을 같은 문서에 적용해 경계 비교 (CHUNKING-TEST.md)

### 하이브리드 검색 (Vector + BM25)
- 단위: KeywordIndexTest — 정확 토큰 상위 랭크, namespace 격리, 무매칭 빈 결과
- 수동: 정확 문자열 질의("INV-2026-0042")로 BM25가 잡는지 확인. hybrid on/off 비교
- 오프라인: eval harness로 hybrid on/off recall 비교

### Reranking
- 오프라인: eval harness가 none/llm/mmr/cross를 한 번에 sweep. 발견 — llm rerank가 recall을 깎을 수 있음(정답 청크를 top-K 밖으로 밀어냄)
- 검증 함정: 처음엔 4조합이 전부 동일 결과였는데, 알고 보니 요청 오버라이드가 RagService까지 배선되지 않아 같은 설정을 4번 측정한 것이었다. "비교가 전부 같으면 변수가 안 먹는지부터 의심하라."

### 검색 품질 종합
- 오프라인: golden.json(8케이스) + run_eval.py. recall 측정. 정답셋 과엄격(silos)과 진짜 검색 실패(ubiquitous-term)를 구분 (EVALUATION.md)

---

## 2. 데이터 계층

### 문서 단위 관리 / 카탈로그
- 수동: `GET /api/data/documents`로 ns+title 그룹핑 확인. `DELETE /api/data/documents`로 문서 단위 일괄 삭제 확인
- 수동(H2 console): `SELECT * FROM document_catalog`로 카탈로그가 Parquet에서 hydrate됐는지 확인

### 임베딩 prefix (nomic)
- 수동: search_query/search_document prefix를 안 맞췄을 때 "What is RAG?"가 엉뚱한 문서를 주던 것을, prefix 짝을 맞춰 해결 (DATA-MODEL.md 10.5)

### 영속화 (H2 file)
- 수동: demo 프로필로 질의 -> `GET /api/governance/stats`의 totalCalls 확인 -> 재시작 -> 같은 stats가 유지되는지(before/after 동일) 확인

---

## 3. 거버넌스

### PII 마스킹
- 단위: PiiRedactionServiceTest — 이메일/전화/SSN 마스킹, 정상문 불변, null 처리. 한국 전화번호(3-4-4) 미마스킹 버그를 이 테스트가 잡음 (TESTING.md)
- 수동: PII 포함 질의 후 `GET /api/governance/logs`의 question이 마스킹됐는지, piiCount가 맞는지 확인

### Provenance (근거 추적)
- 수동: 질의 후 `GET /api/governance/logs`의 sources에 rerank 후 최종 청크가 기록됐는지 확인

### 통계 대시보드
- 수동: `GET /api/governance/stats`로 모델별/소스타입별/총계 집계 확인. H2 console SQL과 교차 검증

### 피드백 루프
- 수동: `/api/rag/ask` 응답의 logId로 `POST /api/governance/feedback` -> logs의 feedback 컬럼 확인 -> stats의 feedback 집계 확인
- UI: 답변 아래 Up/Down 버튼 -> 누적 카운트 표시

---

## 4. 멀티모달

### 비전 + OCR
- 수동: 인보이스 이미지로 `/api/multimodal/ask`. OCR이 정확 숫자(852.50)를 잡고, 비전이 레이아웃을 설명하는지. 비전이 숫자를 환각하던 문제를 OCR/비전 역할 분리로 해결 (MULTIMODAL.md)

---

## 검증은 어떻게 해야할까?

- 결정적 로직은 단위 테스트로 회귀를 막는다.
- 검색처럼 "정답이 모호한" 영역은 정답셋 기반 오프라인 평가로 정량화한다.
- 통합/엔드투엔드는 curl로 빠르게 확인하고, 최종은 UI에서 사람이 본다.
- 측정 도구 자체도 검증한다(오버라이드 미배선 사례). 비교가 전부 같으면 변수부터 의심한다.
- 테스트는 통과시키려는 게 아니라 틀린 가정을 드러내려고 쓴다(한국 전화번호 PII, ubiquitous-term retrieval).

## 한계 

- 통합 테스트(@SpringBootTest)는 외부 의존(Ollama/DB) 때문에 CI 불안정 — 분리 필요
- 답변 생성 품질은 아직 정량 평가 없음(LLM-as-judge 예정)
- A/B 테스트는 실트래픽이 없어 미도입 — 오프라인 평가가 그 선행 단계
