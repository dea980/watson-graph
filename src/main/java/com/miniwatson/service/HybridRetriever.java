package com.miniwatson.service;

import com.miniwatson.data.Article;
import com.miniwatson.data.KeywordIndex;
import com.miniwatson.data.KnowledgeGraph;
import com.miniwatson.data.VectorIndex;
import com.miniwatson.data.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/** 벡터(의미) + 키워드(BM25) + 그래프(관계) 후보를 RRF로 융합. */
@Service
public class HybridRetriever {

    private static final int RRF_K = 60;

    private final VectorStore vectorIndex;
    private final KeywordIndex keywordIndex;
    private final KnowledgeGraph knowledgeGraph;
    private final boolean hybridEnabled;
    private final boolean graphEnabled;
    // 그래프 후보 RRF 가중. 멀티홉 정답은 graph 리스트에만 떠 1/(K+rank) 한 표뿐이라
    // vec/BM25 양쪽에 어중간히 걸린 문서에 밀린다. 배수를 줘 top-N으로 끌어올린다.
    // EVAL에서 스윕하는 튜닝 노브 — 운영값은 application-prod에서 고정한다.
    private final double graphWeight;

    public HybridRetriever(VectorStore vectorIndex,
                           KeywordIndex keywordIndex,
                           KnowledgeGraph knowledgeGraph,
                           @Value("${retrieval.hybrid.enabled:true}") boolean hybridEnabled,
                           @Value("${retrieval.graph.enabled:true}") boolean graphEnabled,
                           @Value("${retrieval.graph.weight:2.0}") double graphWeight) {
        this.vectorIndex = vectorIndex;
        this.keywordIndex = keywordIndex;
        this.knowledgeGraph = knowledgeGraph;
        this.hybridEnabled = hybridEnabled;
        this.graphEnabled = graphEnabled;
        this.graphWeight = graphWeight;
    }
    /** 기본 설정 사용. */
    public List<Article> search(String ns, List<Float>queryEmbedding, String queryText, int topN){
        return search(ns, queryEmbedding, queryText, topN, null, null);
    }
    /** hybridOverride/graphOverride != null 이면 그 값으로 (EVAL-ONLY 경로에서 사용). */
    public List<Article> search(String ns, List<Float> queryEmbedding, String queryText, int topN,
                                Boolean hybridOverride, Boolean graphOverride) {

        boolean useHybrid = (hybridOverride != null) ? hybridOverride : hybridEnabled;
        boolean useGraph  = (graphOverride  != null) ? graphOverride  : graphEnabled;

        List<Article> vec = vectorIndex.search(ns, queryEmbedding, topN);
        if (!useHybrid && !useGraph) return vec;

        // RRF: 각 리스트 순위로 점수 누적
        Map<Long, Double> score = new HashMap<>();
        Map<Long, Article> byId = new HashMap<>();
        rrf(vec, score, byId, 1.0);
        if (useHybrid) rrf(keywordIndex.search(ns, queryText, topN), score, byId, 1.0);
        // 그래프(관계) 후보 — 멀티홉으로 끌어온 article을 graphWeight 배수로 가중 융합
        if (useGraph)  rrf(knowledgeGraph.search(ns, queryText, topN), score, byId, graphWeight);


        return score.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topN)
                .map(e -> byId.get(e.getKey()))
                .toList();
    }

    private void rrf(List<Article> ranked, Map<Long, Double> score, Map<Long, Article> byId, double weight) {
        for (int rank = 0; rank < ranked.size(); rank++) {
            Article a = ranked.get(rank);
            byId.put(a.getId(), a);
            score.merge(a.getId(), weight * (1.0 / (RRF_K + rank + 1)), Double::sum);
        }
    }
}
