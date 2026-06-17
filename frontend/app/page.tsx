'use client';

import { useEffect, useState } from 'react';
import { ask, fetchModels, type RagResult } from '@/lib/api';

// 금융 페르소나 화면. 백엔드 persona.domain(자본시장·공시 전문, 출처 근거, 보수적)을
// 사용자에게 명시적으로 드러낸다. namespace 기본값은 증권 코퍼스(kr-securities).
const DEFAULT_NS = 'kr-securities';

export default function Page() {
  const [question, setQuestion] = useState('');
  const [namespace, setNamespace] = useState(DEFAULT_NS);
  const [models, setModels] = useState<string[]>([]);
  const [model, setModel] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<RagResult | null>(null);

  useEffect(() => {
    fetchModels()
      .then((m) => {
        setModels(m.available ?? []);
        setModel(m.default ?? '');
      })
      .catch(() => {
        // 모델 목록 실패는 치명적이지 않다 — 서버 기본 모델로 진행
      });
  }, []);

  async function onAsk() {
    const q = question.trim();
    if (!q || loading) return;
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const r = await ask({ question: q, namespace, model: model || undefined });
      setResult(r);
    } catch (e) {
      setError(e instanceof Error ? e.message : '알 수 없는 오류');
    } finally {
      setLoading(false);
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    // Cmd/Ctrl + Enter 로 전송
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') onAsk();
  }

  return (
    <main className="wrap">
      <header className="app">
        <h1>
          watson-graph
          <span className="badge">자본시장·공시 RAG</span>
        </h1>
        <div className="persona">
          <b>자본시장·기업공시(DART) 전문 어시스턴트.</b> 공시·재무제표·자본시장법에 근거해
          답하고, 수치와 일정(공시일·기준일)·법적 효력은 보수적으로 다루며 추정으로 단정하지
          않습니다. 모든 답변에는 근거 출처가 함께 표시됩니다.
        </div>
      </header>

      <div className="controls">
        <div className="field">
          <label htmlFor="ns">네임스페이스(테넌트)</label>
          <input
            id="ns"
            value={namespace}
            onChange={(e) => setNamespace(e.target.value)}
            placeholder={DEFAULT_NS}
          />
        </div>
        <div className="field">
          <label htmlFor="model">모델</label>
          <select id="model" value={model} onChange={(e) => setModel(e.target.value)}>
            {models.length === 0 && <option value="">서버 기본</option>}
            {models.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        </div>
      </div>

      <textarea
        value={question}
        onChange={(e) => setQuestion(e.target.value)}
        onKeyDown={onKeyDown}
        placeholder="예: 사업보고서 제출을 의무화한 법률이 파생상품으로 분류하는 증권은?"
      />
      <div className="askbar">
        <button className="primary" onClick={onAsk} disabled={loading || !question.trim()}>
          {loading ? '검색·생성 중…' : '질문하기'}
        </button>
        <span style={{ fontSize: 12, color: 'var(--muted)' }}>⌘/Ctrl + Enter</span>
      </div>

      {error && <div className="error">{error}</div>}

      {result && (
        <>
          <section className="answer">
            <h2>답변</h2>
            <div className="text">{result.answer}</div>
          </section>

          <section className="sources">
            <h2>근거 출처 ({result.sources.length})</h2>
            {result.sources.map((s) => (
              <div className="source" key={s.id}>
                <div className="title">
                  #{s.id} {s.title}
                </div>
                <div className="meta">
                  {s.namespace}
                  {s.url ? (
                    <>
                      {' | '}
                      <a href={s.url} target="_blank" rel="noreferrer">
                        원문
                      </a>
                    </>
                  ) : null}
                </div>
                <div className="snippet">
                  {s.summary.length > 240 ? s.summary.slice(0, 240) + '…' : s.summary}
                </div>
              </div>
            ))}
          </section>

          {result.logId != null && (
            <div className="foot">감사 로그 ID: {result.logId}</div>
          )}
        </>
      )}
    </main>
  );
}
