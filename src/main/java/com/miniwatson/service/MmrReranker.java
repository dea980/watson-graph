package com.miniwatson.service;

import com.miniwatson.data.Article;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component("mmr")
public class MmrReranker implements Reranker {

    private static final double LAMBDA = 0.6;   // 관련도 vs 다양성 균형

    @Override
    public List<Article> rerank(String question, List<Article> candidates, int topK) {
        if (candidates.size() <= topK) return candidates;

        // 후보들은 이미 1차 검색에서 질의 유사도 순으로 정렬돼 있다고 가정.
        // 질의 유사도는 그 순위(rank)를 점수로 근사 (별도 질의 임베딩 재계산 회피).
        List<Article> selected = new ArrayList<>();
        List<Article> pool = new ArrayList<>(candidates);

        while (selected.size() < topK && !pool.isEmpty()) {
            Article best = null;
            double bestScore = -Double.MAX_VALUE;

            for (Article c : pool) {
                double relevance = relevanceByRank(candidates.indexOf(c), candidates.size());
                double maxSimToSelected = 0;
                for (Article s : selected) {
                    maxSimToSelected = Math.max(maxSimToSelected, cosine(c, s));
                }
                double mmr = LAMBDA * relevance - (1 - LAMBDA) * maxSimToSelected;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    best = c;
                }
            }
            selected.add(best);
            pool.remove(best);
        }
        return selected;
    }

    /** 1차 검색 순위를 0~1 관련도로 변환 (1등=1.0). */
    private double relevanceByRank(int rank, int total) {
        return 1.0 - ((double) rank / total);
    }

    private double cosine(Article a, Article b) {
        List<Float> va = a.getEmbedding(), vb = b.getEmbedding();
        if (va == null || vb == null || va.isEmpty()) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < va.size(); i++) {
            dot += va.get(i) * vb.get(i);
            na += va.get(i) * va.get(i);
            nb += vb.get(i) * vb.get(i);
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}