package com.miniwatson.service;

import com.miniwatson.data.Article;
import java.util.List;

public interface Reranker {
    /** 후보들을 질의 관련도로 재정렬해 상위 topK 개 변환*/
    List<Article> rerank(String question, List<Article> candidates, int topK);
}
