package com.miniwatson.data;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 경량 co-occurrence 그래프의 멀티홉 검색 검증 (LLM 0회, 결정적). */
class KnowledgeGraphTest {

    private Article doc(long id, String ns, String title, String summary) {
        Article a = new Article();
        a.setId(id);
        a.setNamespace(ns);
        a.setTitle(title);
        a.setSummary(summary);
        return a;
    }

    private KnowledgeGraph freshGraph() {
        return new KnowledgeGraph(new ArticleRepository() {
            public List<Article> loadAll() { return List.of(); }
            public Article save(Article a) { return a; }
            public void saveAll(List<Article> articles) { }
            public boolean deleteById(long id) { return false; }
        });
    }

    @Test
    void multiHopBridgesEntitiesAcrossDocuments() {
        KnowledgeGraph g = freshGraph();
        // doc1: 금융위원회 <-> 자본시장법
        g.add(doc(1, "default", "자본시장법 개정", "금융위원회가 자본시장법을 개정했다."));
        // doc2: 자본시장법 <-> 상장지수펀드(ETF)  => 금융위원회와 ETF는 2-hop
        g.add(doc(2, "default", "ETF 규제", "자본시장법에 따라 상장지수펀드(ETF) 상장 요건이 정해진다."));
        // doc3: 무관
        g.add(doc(3, "default", "날씨", "오늘 서울은 맑음."));

        List<Article> res = g.search("default", "금융위원회와 ETF 관계", 5);
        List<Long> ids = res.stream().map(Article::getId).toList();

        assertTrue(ids.contains(2L), "2-hop으로 연결된 ETF 문서가 검색돼야 함");
        assertFalse(ids.contains(3L), "엔티티가 없는 무관 문서는 제외돼야 함");
    }

    @Test
    void emptyWhenQueryHasNoDomainEntity() {
        KnowledgeGraph g = freshGraph();
        g.add(doc(1, "default", "자본시장법", "금융위원회가 자본시장법을 개정했다."));
        assertTrue(g.search("default", "점심 뭐 먹지", 5).isEmpty());
    }

    @Test
    void namespaceIsolation() {
        KnowledgeGraph g = freshGraph();
        g.add(doc(1, "tenantA", "자본시장법", "금융위원회 자본시장법"));
        assertTrue(g.search("tenantB", "금융위원회", 5).isEmpty(), "다른 네임스페이스는 격리돼야 함");
        assertFalse(g.search("tenantA", "금융위원회", 5).isEmpty());
    }
}
