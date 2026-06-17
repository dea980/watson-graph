[사용자]
↓ POST /api/rag/ask
↓ Body: { "question": "What is RAG?", "namespace": "default", "model": null, "rerank": null, "hybrid": null }

[RagController]  @RequestMapping("/api/rag") + @PostMapping("/ask")
↓ @RequestBody로 AskRequest 객체에 매핑됨
↓ ragService.ask(question, namespace, model, rerank, hybrid) 호출

[RagService] — RAG 파이프라인
↓ 1. EmbeddingService.embed("search_query: " + question) → 768차원 질의 벡터
↓ 2. HybridRetriever.search(ns, qEmb, qText, FETCH_N=20, hybrid)
↓    VectorIndex(cosine) + KeywordIndex(BM25) 후보를 RRF(k=60)로 융합
↓ 3. Reranker.rerank(question, candidates, TOP_K=2)  (기본 mmr)
↓ 4. augmented prompt 구성 (context 청크 + question)
↓ 5. OllamaService.generate(prompt, model) 호출

[OllamaService]
↓ OllamaRequest 만들고 RestTemplate.postForObject(.../api/generate)
↓ QueryLog 저장 (question, answer, model, latencyMs, sources, piiCount, augmentedPrompt)
↓    PII 마스킹 적용 ([CARD][SSN][EMAIL][PHONE])

[Ollama:11434]
↓ ibm/granite4:latest 추론 → grounded answer

[RagService]
↓ RagResult{ answer, sources(List<Article>), logId } 반환

[사용자]
↓ { "answer": "RAG is...", "sources": [...], "logId": 123 }
