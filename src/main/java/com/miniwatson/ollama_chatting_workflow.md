[브라우저/curl]
↓ POST /api/rag/ask
[RagController]
↓ AskRequest 매핑 → ragService.ask(...)
[RagService]
↓ embed(search_query:) → HybridRetriever(vector+BM25 RRF) → Reranker(top-K=2) → augmented prompt
↓ OllamaService.generate(prompt, model)
[Ollama API]
↓ ibm/granite4:latest 모델 추론
[OllamaService]
↓ JSON 파싱 + QueryLog 저장(latency·PII·sources)
[RagService]
↓ RagResult{answer, sources, logId}
[curl 응답에 표시]
