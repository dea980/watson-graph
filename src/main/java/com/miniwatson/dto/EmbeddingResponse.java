package com.miniwatson.dto;

import lombok.Data;
import java.util.List;

@Data
public class EmbeddingResponse {
    private String model;
    // 2D array 이기에  위와 같은 형태
    private List<List<Float>> embeddings;
}
