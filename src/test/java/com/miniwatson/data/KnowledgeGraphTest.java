package com.miniwatson.data;

import com.miniwatson.service.DomainGlossary.EdgeType;
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

    // bridge 엔티티(자본시장법)와 목표 엔티티(상장지수펀드)가 한 문서의 서로 다른 청크로 갈린 상황.
    // add()는 청크 1개 안의 엔티티만 잇기 때문에 자본시장법-상장지수펀드 엣지가 안 생긴다.
    private KnowledgeGraph chunkSplitGraph() {
        KnowledgeGraph g = freshGraph();
        g.add(doc(1, "default", "의결",      "금융위원회가 자본시장법을 의결했다."));   // 문서A: 금융위원회-자본시장법 엣지
        g.add(doc(2, "default", "근거 #1",   "본 상품은 자본시장법에 근거한다."));        // 문서B 청크1: 자본시장법만
        g.add(doc(3, "default", "근거 #2",   "상장지수펀드(ETF) 상장 요건."));            // 문서B 청크2: 상장지수펀드만
        return g;
    }

    @Test
    void chunkSplitBreaksMultihopWithoutDocLevelEdges() {
        KnowledgeGraph g = chunkSplitGraph();
        // 금융위원회 -> 자본시장법(1-hop)까지는 가지만, 자본시장법-상장지수펀드 엣지가 없어 ETF 청크엔 도달 못 함.
        List<Long> ids = g.search("default", "금융위원회 관련", 5).stream().map(Article::getId).toList();
        assertFalse(ids.contains(3L), "청크가 갈리면 멀티홉이 끊겨 ETF 청크(3)에 도달하지 못해야 함");
    }

    @Test
    void linkDocumentRestoresMultihopAcrossChunks() {
        KnowledgeGraph g = chunkSplitGraph();
        // 문서B의 두 청크(2,3)를 문서 단위로 연결 -> 자본시장법-상장지수펀드 엣지 생성.
        g.linkDocument("default", java.util.Set.of("자본시장법", "상장지수펀드"));
        List<Long> ids = g.search("default", "금융위원회 관련", 5).stream().map(Article::getId).toList();
        assertTrue(ids.contains(3L), "linkDocument 후 bridge가 이어져 ETF 청크(3)에 도달해야 함");
    }

    @Test
    void seedRelationProducesTypedEdgeAndPath() {
        KnowledgeGraph g = freshGraph();
        // 시드표: (한국거래소, CALCULATES, 코스피200). 한 문서에 같이 등장 → 타입드 엣지.
        g.add(doc(1, "default", "지수산출", "한국거래소가 코스피200을 산출한다."));
        g.add(doc(2, "default", "지수상세", "코스피200은 한국 대표 지수다."));

        // 질문은 한국거래소에만 앵커. 코스피200 문서(2)는 CALCULATES 엣지로 1-hop 도달.
        List<KnowledgeGraph.GraphHit> hits = g.searchWithPaths("default", "한국거래소 지수 산출", 5);
        KnowledgeGraph.GraphHit hit2 = hits.stream()
                .filter(h -> h.article().getId() == 2L).findFirst().orElse(null);

        assertNotNull(hit2, "코스피200 문서가 타입드 엣지로 도달돼야 함");
        assertTrue(hit2.path().stream().anyMatch(s -> s.type() == EdgeType.CALCULATES),
                "도달 경로에 CALCULATES 관계가 드러나야 함(근거 노출)");
    }
}
