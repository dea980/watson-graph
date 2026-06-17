[curl POST]
↓
[RagController] → @RequestMapping("/api/rag") + @PostMapping("/ask")
↓
[RagService] → embed → HybridRetriever → Reranker(top-K=2) → augmented prompt
↓
[OllamaService.generate]
├── startTime 기록
├── Ollama /api/generate 호출 (5-30초)
├── latency 계산
├── PII 마스킹 (question/answer → [CARD][SSN][EMAIL][PHONE])
└── QueryLog DB 저장 (question, answer, model, latencyMs, sources, piiCount, augmentedPrompt)
↓
[답변 반환] → RagResult{answer, sources, logId}
