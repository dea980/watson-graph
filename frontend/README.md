# watson-graph 프론트엔드 (Next.js)

자본시장·기업공시(DART) 도메인 RAG/GraphRAG UI. 백엔드(Spring, `watson-graph`)의 `/api/rag/*` 를 소비한다.

## 스택

Next.js(App Router) + React + TypeScript. SSR은 쓰지 않는다(데이터는 전부 백엔드 API). 클라이언트 렌더로 동작하고, 배포 시 정적 산출(static export)도 가능하다.

## 로컬 실행 (분리, dev)

백엔드를 먼저 띄운다(기본 `http://localhost:8080`).

```bash
cd frontend
cp .env.example .env        # 필요 시 BACKEND_ORIGIN 수정
npm install
npm run dev                 # http://localhost:3000
```

`next.config.mjs`의 rewrites가 `/api/*` 를 `BACKEND_ORIGIN`으로 프록시한다. 같은 오리진 호출이라 브라우저 CORS가 없다.

## 빌드 — 두 배포 모델

코드는 동일하고 빌드 플래그만 다르다.

**1) jar 통합 (단일 배포, 금융 도메인 권장)**
```bash
npm run build:static        # BUILD_TARGET=static → out/ 정적 산출
# out/ 의 산출물을 Spring 의 src/main/resources/static/ 으로 복사해 단일 jar로 배포
```
프론트와 `/api`를 Spring이 같은 오리진으로 서빙하므로 CORS·프록시가 필요 없다. 배포 아티팩트가 1개라 공격 표면과 운영 비용이 작다.

**2) 분리 배포 (decoupled)**
```bash
npm run build && npm start  # 또는 정적 호스팅/Vercel
```
백엔드가 다른 도메인이면 `NEXT_PUBLIC_API_BASE`로 백엔드 오리진을 지정하고, Spring 쪽 CORS 허용 오리진을 와일드카드가 아닌 고정 도메인으로 설정한다.

## 구조

```
app/
  layout.tsx     루트 레이아웃, 메타데이터
  page.tsx       금융 페르소나 RAG 화면(질문/모델/네임스페이스/답변/출처)
  globals.css    스타일
lib/
  api.ts         타입드 API 클라이언트 (ask, fetchModels)
```

## 노출 범위(거버넌스)

검색전략 토글(rerank/hybrid/graph)은 백엔드에서 EVAL-ONLY로 게이트된다. 일반 사용자 화면에서 검색전략을 바꾸지 못하게 하므로 이 UI에는 싣지 않는다. 평가는 `eval/` 하니스로 수행한다.

## 다음(로드맵)

그래프 경로 시각화 화면(U2)은 엔진의 경로 반환 API(T1-A) 이후. `docs/ROADMAP.md`, `docs/GRAPHRAG-DOMAIN-DESIGN.md` 참고.
