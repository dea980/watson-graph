// watson-graph 백엔드(Spring) API 클라이언트.
//
// base: NEXT_PUBLIC_API_BASE 가 있으면 그걸로(분리배포에서 다른 도메인 백엔드 직접 호출),
// 없으면 상대경로 ''(같은 오리진 /api — dev 프록시 또는 jar 통합).
//
// 노출 범위 주의: rerank/hybrid/graph 는 서버에서 EVAL-ONLY로 게이트되는 검색전략 토글이다.
// 운영 거버넌스상 일반 사용자 화면에서 검색전략을 바꾸지 못하게 하므로, 이 클라이언트에 싣지 않는다.

const BASE = process.env.NEXT_PUBLIC_API_BASE ?? '';

/** /api/rag/ask 응답의 출처 1건 (Article). */
export interface Source {
  id: number;
  namespace: string;
  title: string;
  summary: string;
  url: string;
  ingestedAt: string;
}

/** /api/rag/ask 응답. answer=생성 답변, sources=근거, logId=감사 로그 id. */
export interface RagResult {
  answer: string;
  sources: Source[];
  logId: number | null;
}

/** /api/rag/models 응답. */
export interface ModelsResponse {
  default: string;
  available: string[];
}

export interface AskParams {
  question: string;
  namespace?: string;
  model?: string;
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`요청 실패 (${res.status}): ${text || res.statusText}`);
  }
  return res.json() as Promise<T>;
}

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Error(`요청 실패 (${res.status}): ${res.statusText}`);
  return res.json() as Promise<T>;
}

/** RAG 질의. 빈 namespace/model 은 서버 기본값을 쓰도록 생략한다. */
export function ask(params: AskParams): Promise<RagResult> {
  const body: AskParams = { question: params.question };
  if (params.namespace) body.namespace = params.namespace;
  if (params.model) body.model = params.model;
  return postJson<RagResult>('/api/rag/ask', body);
}

/** 선택 가능한 chat 모델 목록과 기본 모델. */
export function fetchModels(): Promise<ModelsResponse> {
  return getJson<ModelsResponse>('/api/rag/models');
}
