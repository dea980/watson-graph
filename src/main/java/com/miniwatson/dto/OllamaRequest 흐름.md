OllamaService.generate(augmentedPrompt, model)  // RAG 파이프라인이 augmented prompt로 호출
↓
[1] OllamaRequest 객체 만듦
model="ibm/granite4:latest", prompt=augmented RAG prompt, stream=false, think=false, options{num_predict:256}
↓
[2] postForObject 호출
- request 객체 → JSON 변환 (Jackson)
- HTTP POST http://localhost:11434/api/generate
- Body: {"model":"ibm/granite4:latest","prompt":"...context + question...","stream":false,"think":false,"options":{"num_predict":256}}
↓
[3] Ollama 답변
JSON: {"model":"ibm/granite4:latest","response":"RAG is...","done":true}
↓
[4] postForObject 반환
- JSON → OllamaResponse 객체 (Jackson)
- response.response = "RAG is..."
↓
[5] response.getResponse() 반환
→ "RAG is..."

참고: gemma4는 whitelist 대체 모델이고 기본값은 ibm/granite4:latest. 호출 직후 QueryLog로 감사 로그 저장.
