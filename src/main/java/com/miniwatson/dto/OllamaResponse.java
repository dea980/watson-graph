package com.miniwatson.dto;
import java.util.List;
import lombok.Data;

@Data
public class OllamaResponse {
    private String model;
    private String createdAt;
    private String response;
    private boolean done;
//    private List<Integer> context;
//    private Long totalDuration;
//    private Long loadDuration;
//    private Integer promptEvalCount;
//    private Integer evalCount;
}

/*
 * Full Ollama API response fields (참고용):
 * - model: String
 * - created_at: String (ISO datetime)
 * - response: String (the answer text)
 * - done: boolean
 * - context: List<Integer>
 * - total_duration: Long (nanoseconds)
 * - load_duration: Long
 * - prompt_eval_count: Integer
 * - eval_count: Integer
 *
 * 필요할 때 추가:
 * - latency 측정 → total_duration
 * - 컨텍스트 유지 → context
 */