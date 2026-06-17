import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'watson-graph — 자본시장·공시 RAG',
  description:
    '자본시장·기업공시(DART) 도메인 GraphRAG. 벡터+BM25+지식그래프(관계)를 융합해 멀티홉 질문에 출처 근거로 답합니다.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
