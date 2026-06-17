> 참고: 이건 초기 빌드 순서 기록(Day 5)이다. 최종 검색 구조는 hybrid(벡터+BM25) + reranking으로 진화했다 — docs/ARCHITECTURE.md, docs/HYBRID-SEARCH.md 참조.


## 전체 진행 순서 (효율 우선)

**전략**: A → D → B → C → E (서로 의존성 고려)

| # | Day | 시간 | 누적 시간 |
|---|---|---|---|
| 1 | **A: Day 5 - RAG 통합** | 3-4h | 4h |
| 2 | **D: 응답 컨트롤** | 30m | 4.5h |
| 3 | **B: Day 4b - Parquet** | 2-3h | 7h |
| 4 | **C: Day 6 - Frontend** | 2-3h | 10h |
| 5 | **E: README + Demo 영상** | 1-2h | 12h |

**총 예상**: 10-12시간 (밤샘 모드 X, 효율 모드)

---

# 5 시작 — RAG 통합

## Architecture — 본인 mini watsonx가 진짜 RAG로

```
[POST /api/rag/ask {"question": "..."}]
   ↓
[RagController]
   ↓
[RagService]
   ├── 1. EmbeddingService.embed("search_query: " + 질문) → 벡터 (nomic-embed-text, 768차원)
   ├── 2. HybridRetriever: VectorIndex(cosine) + KeywordIndex(BM25) 후보를 RRF로 융합
   ├── 3. Reranker: 후보 재정렬 → top-K=2 (기본 mmr)
   ├── 4. augmented prompt 구성: question + context 청크
   ├── 5. OllamaService.generate(prompt, model) → grounded answer
   └── 6. QueryLog 저장 (latency·PII·sources)
   ↓
[RagResult{answer, sources, logId} 반환]
```

---

## 본인이 만들 것 — 6단계

```
Step 1: EmbeddingService.java (Ollama embed API 호출)
Step 2: Article.java 수정 (embedding 필드 추가)
Step 3: IngestionService.java 수정 (ingest 시 embedding 생성)
Step 4: RagService.java (유사도 검색 + augmented prompt)
Step 5: RagController.java (/api/rag/ask)
Step 6: 테스트 + GitHub commit
```

---

## Step 1: EmbeddingService.java

### 역할
- Ollama의 `/api/embed` endpoint 호출
- 텍스트 → embedding 벡터 (List<Float>)

### Ollama embed API 확인

먼저 Terminal에서 테스트:
```bash
curl http://localhost:11434/api/embed -d '{
  "model": "nomic-embed-text",
  "input": "What is RAG?"
}'
```

**응답 예시** (embedding 일부만):
```json
{
  "model": "nomic-embed-text",
  "embeddings": [
    [0.123, -0.456, 0.789, ...]
  ]
}
```

→ `embeddings`는 **2D 배열** (배치 처리용). 단일 input이면 첫 번째 요소 사용.

---

### DTO — 새로 만들 2개

#### `dto/EmbeddingRequest.java`

```java
package com.miniwatson.dto;

import lombok.Data;

@Data
public class EmbeddingRequest {
    private String model;
    private String input;
}
```

#### `dto/EmbeddingResponse.java`

```java
package com.miniwatson.dto;

import lombok.Data;
import java.util.List;

@Data
public class EmbeddingResponse {
    private String model;
    private List<List<Float>> embeddings;
}
```

→ 2D 배열이라 `List<List<Float>>`.

---

### `service/EmbeddingService.java`

```java
package com.miniwatson.service;

import com.miniwatson.dto.EmbeddingRequest;
import com.miniwatson.dto.EmbeddingResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class EmbeddingService {

    private final String OLLAMA_EMBED_URL = "http://localhost:11434/api/embed";
    private final String EMBED_MODEL = "nomic-embed-text";
    private final RestTemplate restTemplate = new RestTemplate();

    public List<Float> embed(String text) {
        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel(EMBED_MODEL);
        request.setInput(text);

        EmbeddingResponse response = restTemplate.postForObject(
                OLLAMA_EMBED_URL,
                request,
                EmbeddingResponse.class
        );

        if (response == null || response.getEmbeddings() == null || response.getEmbeddings().isEmpty()) {
            throw new RuntimeException("No embedding returned for text: " + text);
        }

        // 첫 번째 embedding 반환 (단일 input)
        return response.getEmbeddings().get(0);
    }
}
```


---

## 핵심 흐름

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

---


```
□ 1. Terminal에서 curl로 nomic-embed-text 테스트
□ 2. EmbeddingRequest.java 만들기 (5줄)
□ 3. EmbeddingResponse.java 만들기 (5줄)
□ 4. EmbeddingService.java 만들기 (30줄)
□ 5. 빨간 줄 없는지 확인
□ 6. 코드 share
```

---

## Step 1 끝나면 — 통합 테스트

Spring Boot 재시작 후 (직접 컨트롤러 없이도 OK):

```bash
# Terminal에서 직접 Ollama 호출 가능 확인
curl http://localhost:11434/api/embed -d '{
  "model": "nomic-embed-text",
  "input": "test"
}'
```

→ embedding 벡터 받으면 Ollama 준비됨.

---

## Step 1 끝낸 후 다음

### Step 2: Article에 embedding 필드 추가
### Step 3: IngestionService에 embedding 생성 통합
### Step 4: RagService (similarity search + prompt augmentation)
### Step 5: RagController (/api/rag/ask)
### Step 6: 테스트

→ 본인이 Step 1 끝나면 Step 2-6 차례차례 진행.

---
