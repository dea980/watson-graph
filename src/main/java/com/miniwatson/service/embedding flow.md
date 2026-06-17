```
embed("What is RAG?")
↓
[EmbeddingRequest 객체 생성]
model: "nomic-embed-text"
input: "What is RAG?"
↓
[Ollama POST /api/embed]
↓
[EmbeddingResponse 받음]
embeddings: [[0.123, -0.456, ...]]
↓
[첫 번째 embedding 추출]
↓
[List<Float> 반환] — 768차원 벡터
```
768차원 벡터

nomic-embed-text는 768차원 vector 생성
다른 모델: OpenAI ada-002 = 1536, BGE = 384, 등 다양
차원 = 모델이 텍스트의 "의미"를 표현하는 공간의 크기
같은 의미 텍스트끼리는 벡터 공간에서 가까움 (cosine similarity ↑)

같은 embedder를 질의/저장에 prefix만 다르게 씀: 질의는 "search_query:", 저장 청크는 "search_document:".