package com.miniwatson.data;

import java.util.List;
import java.util.Map;

public interface VectorStore {
    void add(Article a);
    void rebuild(List<Article> all);
    List<Article> search(String namespace,List<Float> query, int topK);
    Map<String, Object> stats();
}
