package com.miniwatson.data;

import com.miniwatson.service.DomainEntityExtractor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/**
 * 경량 공출현(co-occurrence) 지식그래프. VectorIndex(의미) · KeywordIndex(어휘)의 세 번째 짝(관계).
 *
 * 왜: 벡터/BM25는 "비슷한 청크"는 잘 찾지만 "A→B→C로 이어지는 관계"는 한 청크에 안 담겨 못 찾는다.
 * 도메인 엔티티(기관·법령·상품)를 노드로, 같은 문서에 함께 등장하면 엣지를 연결해 멀티홉 탐색을 가능케 한다.
 *
 * 로컬 친화: 엔티티 추출은 DomainEntityExtractor(사전+규칙, LLM 0회). 인덱싱이 임베딩 수준으로 빠르고 결정적.
 * namespace별 분리(테넌트 격리). KeywordIndex와 동일한 수명주기(@PostConstruct hydrate, add/rebuild).
 */
@Component
public class KnowledgeGraph {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraph.class);

    // 멀티홉 점수 감쇠: 직접 매칭 1.0, 1-hop 0.5, 2-hop 0.25
    private static final double[] HOP_DECAY = {1.0, 0.5, 0.25};
    private static final int MAX_HOP = 2;

    private final ArticleRepository store;
    private final Map<String, NsGraph> namespaces = new HashMap<>();

    public KnowledgeGraph(ArticleRepository store) {
        this.store = store;
    }

    /** namespace 1개의 그래프 상태. */
    private static class NsGraph {
        final Map<Long, Article> articles = new HashMap<>();
        // 엔티티 -> 그 엔티티가 등장한 article id 집합
        final Map<String, Set<Long>> entityArticles = new HashMap<>();
        // 엔티티 -> (이웃 엔티티 -> 공출현 횟수)
        final Map<String, Map<String, Integer>> adjacency = new HashMap<>();
    }

    @PostConstruct
    public synchronized void hydrate() {
        try {
            rebuild(store.loadAll());
            log.info("[KnowledgeGraph] hydrated {} namespace(s)", namespaces.size());
        } catch (IOException e) {
            log.warn("[KnowledgeGraph] hydrate failed: {}", e.getMessage());
        }
    }

    public synchronized void rebuild(List<Article> articles) {
        namespaces.clear();
        for (Article a : articles) add(a);
    }

    /** article을 그래프에 반영: 엔티티 추출 → 노드/엣지 갱신. */
    public synchronized void add(Article a) {
        if (a == null) return;
        String ns = nsKey(a.getNamespace());
        NsGraph g = namespaces.computeIfAbsent(ns, k -> new NsGraph());
        g.articles.put(a.getId(), a);

        String text = (a.getTitle() == null ? "" : a.getTitle()) + "\n"
                + (a.getSummary() == null ? "" : a.getSummary());
        List<String> ents = new ArrayList<>(DomainEntityExtractor.extract(text));

        for (String e : ents) {
            g.entityArticles.computeIfAbsent(e, k -> new HashSet<>()).add(a.getId());
        }
        // 같은 article 내 모든 엔티티 쌍을 무방향 엣지로 연결(공출현)
        for (int i = 0; i < ents.size(); i++) {
            for (int j = i + 1; j < ents.size(); j++) {
                link(g, ents.get(i), ents.get(j));
                link(g, ents.get(j), ents.get(i));
            }
        }
    }

    private void link(NsGraph g, String from, String to) {
        g.adjacency.computeIfAbsent(from, k -> new HashMap<>()).merge(to, 1, Integer::sum);
    }

    /**
     * 질의에서 도메인 엔티티를 시드로 잡아 멀티홉 탐색 → 관련 article을 점수순으로 반환.
     * 직접 등장 article 가중치가 가장 크고, 홉이 멀어질수록 감쇠한다.
     */
    public synchronized List<Article> search(String namespace, String queryText, int topK) {
        NsGraph g = namespaces.get(nsKey(namespace));
        if (g == null || g.articles.isEmpty() || queryText == null) return List.of();

        Set<String> seeds = DomainEntityExtractor.extract(queryText);
        if (seeds.isEmpty()) return List.of();

        Map<Long, Double> artScore = new HashMap<>();
        Set<String> visited = new HashSet<>();
        // BFS 프런티어: (엔티티, 홉)
        Deque<String> frontier = new ArrayDeque<>();
        Map<String, Integer> hopOf = new HashMap<>();
        for (String s : seeds) {
            if (g.entityArticles.containsKey(s) || g.adjacency.containsKey(s)) {
                frontier.add(s);
                hopOf.put(s, 0);
            }
        }

        while (!frontier.isEmpty()) {
            String e = frontier.poll();
            int hop = hopOf.getOrDefault(e, 0);
            if (!visited.add(e)) continue;

            double decay = HOP_DECAY[Math.min(hop, HOP_DECAY.length - 1)];
            Set<Long> arts = g.entityArticles.get(e);
            if (arts != null) {
                for (Long id : arts) artScore.merge(id, decay, Double::sum);
            }
            if (hop < MAX_HOP) {
                Map<String, Integer> nbrs = g.adjacency.get(e);
                if (nbrs != null) {
                    int maxW = nbrs.values().stream().max(Integer::compareTo).orElse(1);
                    for (Map.Entry<String, Integer> n : nbrs.entrySet()) {
                        String nb = n.getKey();
                        if (visited.contains(nb)) continue;
                        // 공출현 강도로 이웃 우선순위 부여(현재는 홉 진입만, 가중은 decay에 반영)
                        hopOf.putIfAbsent(nb, hop + 1);
                        frontier.add(nb);
                    }
                    // maxW는 향후 엣지 가중 정규화 확장 지점
                    if (maxW <= 0) maxW = 1;
                }
            }
        }

        return artScore.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(topK)
                .map(en -> g.articles.get(en.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    public synchronized Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        int nodes = 0, edges = 0, arts = 0;
        for (NsGraph g : namespaces.values()) {
            nodes += g.entityArticles.size();
            arts += g.articles.size();
            for (Map<String, Integer> m : g.adjacency.values()) edges += m.size();
        }
        out.put("namespaces", namespaces.size());
        out.put("entities", nodes);
        out.put("edges", edges / 2);   // 무방향(양방향 저장) 보정
        out.put("articles", arts);
        return out;
    }

    private static String nsKey(String ns) {
        return (ns == null || ns.isBlank()) ? "default" : ns.trim();
    }
}
