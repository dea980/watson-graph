package com.miniwatson.service;

import com.miniwatson.data.Article;
import org.springframework.stereotype.Component;
import java.util.List;

@Component("none")
public class NoopReranker implements Reranker {
    @Override
    public List<Article> rerank(String question, List<Article> candidates, int topK) {
        return candidates.size() <= topK ? candidates : candidates.subList(0, topK);
    }
}
