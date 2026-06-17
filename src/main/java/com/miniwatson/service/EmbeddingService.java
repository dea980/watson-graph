package com.miniwatson.service;

import com.miniwatson.dto.EmbeddingRequest;
import com.miniwatson.dto.EmbeddingResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
@Service
public class EmbeddingService {
//    private final String OllAMA_EMBED_URL = "http://localhost:11434/api/embed";
    @Value("${ollama.url}")
    private String ollamaUrl;

//    private final String EMBED_MODEL = "nomic-embed-text";
    @Value("${ollama.embed-model}")
    private String embedModel;
    private final RestTemplate restTemplate = buildTimeoutRestTemplate();

    /** 임베딩 호출 무한대기 방지. 연결 5s/읽기 30s(임베딩은 빠름). */
    private static RestTemplate buildTimeoutRestTemplate() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(java.time.Duration.ofSeconds(5));
        f.setReadTimeout(java.time.Duration.ofSeconds(30));
        return new RestTemplate(f);
    }

    public List<Float> embedQuery(String text){
        return embed(prefixFor(embedModel).q() + text);
    }
    public List<Float> embedDocument(String text){
        return embed(prefixFor(embedModel).d() + text);
    }
    /** 모델명 -> prefix 규약. 순수 함수라 단위 테스트 가능 (EmbeddingServiceTest). */
    static Prefix prefixFor(String model){
        String m = model == null ? "" : model.toLowerCase();
        if (m.startsWith("nomic"))             return new Prefix("search_query: ", "search_document: ");
        if (m.startsWith("granite-embedding")) return new Prefix("", "");   // prefix 불필요
        if (m.startsWith("mxbai"))             return new Prefix(
                "Represent this sentence for searching relevant passages: ", "");
        return new Prefix("", "");             // 기본: 무prefix (안전)
    }
    record Prefix(String q, String d) {}
    public List<Float> embed(String text) {
        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel(embedModel);
        request.setInput(text);

        EmbeddingResponse response = restTemplate.postForObject(
                ollamaUrl + "/api/embed",
                request,
                EmbeddingResponse.class
        );

        if (response == null || response.getEmbeddings() == null || response.getEmbeddings().isEmpty()){
            throw new RuntimeException("No embedding returned for text: " + text);
        }
        return response.getEmbeddings().get(0);
    }

}
