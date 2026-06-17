package com.miniwatson.data;

import com.miniwatson.service.DomainEntityExtractor;
import com.miniwatson.service.DomainGlossary;
import com.miniwatson.service.DomainGlossary.EdgeType;
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

    // 홉당 감쇠 계수. 시드 1.0에서 이웃으로 갈 때마다 곱한다(엣지 가중과 함께).
    // 균일 가중(모든 공출현 1회)이면 시드 1.0, 1-hop 0.5, 2-hop 0.25로 기존 거동과 같다.
    private static final double HOP_STEP = 0.5;
    private static final int MAX_HOP = 2;
    // 약한 엣지 가지치기 임계값(공출현 횟수). 기본 1 = 가지치기 없음.
    // 실데이터에서 우연 1회 공출현 노이즈를 줄이려면 2 이상으로 올린다.
    private static final int MIN_EDGE_WEIGHT = 1;

    // 타입 신뢰도. 시드/규칙으로 타입이 정해진 관계는 1.0, 타입 미상 공출현은 낮게 줘
    // 타입드 경로가 우선되게 한다. 모든 엣지가 UNTYPED면 균일하게 곱해져 v1 상대순위가 보존된다.
    private static double typeConfidence(EdgeType t) {
        return t == EdgeType.UNTYPED ? 0.5 : 1.0;
    }

    /** 멀티홉 경로 1단계: from -(type)-> to. 그래프 시각화/근거 노출용. */
    public record PathStep(String from, EdgeType type, String to) {}
    /** 그래프 검색 결과 1건: article + 점수 + 도달 경로(대표 1개). */
    public record GraphHit(Article article, double score, List<PathStep> path) {}

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
        // 엔티티 -> (이웃 엔티티 -> (관계 타입 -> 공출현 횟수)). 같은 이웃에 복수 타입 가능.
        final Map<String, Map<String, Map<EdgeType, Integer>>> adjacency = new HashMap<>();
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
        // 같은 article 내 모든 엔티티 쌍을 엣지로 연결(공출현). 시드 관계가 있으면 그 타입, 없으면 UNTYPED.
        for (int i = 0; i < ents.size(); i++) {
            for (int j = i + 1; j < ents.size(); j++) {
                String a1 = ents.get(i), b1 = ents.get(j);
                EdgeType t = typeOrUntyped(a1, b1);
                link(g, a1, b1, t);
                link(g, b1, a1, t);
            }
        }
    }

    private static EdgeType typeOrUntyped(String a, String b) {
        EdgeType t = DomainGlossary.relationType(a, b);
        return t == null ? EdgeType.UNTYPED : t;
    }

    private void link(NsGraph g, String from, String to, EdgeType type) {
        g.adjacency.computeIfAbsent(from, k -> new HashMap<>())
                   .computeIfAbsent(to, k -> new EnumMap<>(EdgeType.class))
                   .merge(type, 1, Integer::sum);
    }

    /**
     * 문서 단위 공출현 엣지 생성. 한 문서가 여러 청크로 갈려 서로 다른 청크에 흩어진 엔티티끼리도
     * 같은 문서면 연결한다. add()는 청크(Article) 1개 안의 엔티티만 잇기 때문에, 긴 공시·사업보고서가
     * 청킹되면 bridge 엔티티가 청크 경계에서 끊겨 멀티홉이 깨진다. 그 구멍을 막는 보강 단계.
     *
     * entityArticles(엔티티->청크 매핑)는 일부러 건드리지 않는다. 그건 add()가 청크 단위로 정확히
     * 채워 검색이 올바른 청크를 돌려주게 둔다. 여기서는 엣지(관계)만 문서 전체 기준으로 잇는다.
     * 호출 측(IngestionService)이 청크 루프가 끝난 뒤 문서당 1회 호출한다.
     */
    public synchronized void linkDocument(String namespace, Set<String> docEntities) {
        if (docEntities == null || docEntities.size() < 2) return;
        NsGraph g = namespaces.computeIfAbsent(nsKey(namespace), k -> new NsGraph());
        List<String> ents = new ArrayList<>(docEntities);
        for (int i = 0; i < ents.size(); i++) {
            for (int j = i + 1; j < ents.size(); j++) {
                String a1 = ents.get(i), b1 = ents.get(j);
                EdgeType t = typeOrUntyped(a1, b1);
                link(g, a1, b1, t);
                link(g, b1, a1, t);
            }
        }
    }

    /**
     * 질의에서 도메인 엔티티를 시드로 잡아 멀티홉 탐색 → 관련 article을 점수순으로 반환.
     * 직접 등장 article 가중치가 가장 크고, 홉이 멀어질수록 감쇠한다.
     */
    public synchronized List<Article> search(String namespace, String queryText, int topK) {
        return searchWithPaths(namespace, queryText, topK).stream()
                .map(GraphHit::article)
                .toList();
    }

    /**
     * search와 동일한 멀티홉 탐색이되, article마다 도달 경로(대표 1개)를 함께 반환한다.
     * 경로는 (from -(EdgeType)-> to) 시퀀스라 그래프 시각화/답변 근거로 노출할 수 있다.
     */
    public synchronized List<GraphHit> searchWithPaths(String namespace, String queryText, int topK) {
        NsGraph g = namespaces.get(nsKey(namespace));
        if (g == null || g.articles.isEmpty() || queryText == null) return List.of();

        Set<String> seeds = DomainEntityExtractor.extract(queryText);
        if (seeds.isEmpty()) return List.of();

        Map<Long, Double> artScore = new HashMap<>();
        Map<Long, Double> bestEntW = new HashMap<>();    // article별 최대 단일 기여 가중
        Map<Long, String> bestEntForArt = new HashMap<>(); // 그 기여를 준 엔티티(경로 복원 시작점)
        Set<String> visited = new HashSet<>();
        // BFS 프런티어 + 엔티티별 전파 가중치 + 경로 부모/엣지타입(최강 경로 기준).
        Deque<String> frontier = new ArrayDeque<>();
        Map<String, Integer> hopOf = new HashMap<>();
        Map<String, Double> entWeight = new HashMap<>();
        Map<String, String> parentOf = new HashMap<>();
        Map<String, EdgeType> edgeTypeOf = new HashMap<>();
        for (String s : seeds) {
            if (g.entityArticles.containsKey(s) || g.adjacency.containsKey(s)) {
                frontier.add(s);
                hopOf.put(s, 0);
                entWeight.put(s, 1.0);
            }
        }

        while (!frontier.isEmpty()) {
            String e = frontier.poll();
            int hop = hopOf.getOrDefault(e, 0);
            if (!visited.add(e)) continue;

            double w = entWeight.getOrDefault(e, 0.0);
            Set<Long> arts = g.entityArticles.get(e);
            if (arts != null) {
                for (Long id : arts) {
                    artScore.merge(id, w, Double::sum);
                    if (w > bestEntW.getOrDefault(id, -1.0)) {
                        bestEntW.put(id, w);
                        bestEntForArt.put(id, e);
                    }
                }
            }
            if (hop < MAX_HOP) {
                Map<String, Map<EdgeType, Integer>> nbrs = g.adjacency.get(e);
                if (nbrs != null) {
                    int maxW = nbrs.values().stream().mapToInt(KnowledgeGraph::edgeWeight).max().orElse(1);
                    if (maxW <= 0) maxW = 1;
                    for (Map.Entry<String, Map<EdgeType, Integer>> n : nbrs.entrySet()) {
                        String nb = n.getKey();
                        if (visited.contains(nb)) continue;
                        int wEdge = edgeWeight(n.getValue());
                        if (wEdge < MIN_EDGE_WEIGHT) continue;          // 약한 엣지 가지치기
                        EdgeType dom = dominantType(n.getValue());
                        // 엣지 강도 정규화(wEdge/maxW) x 타입 신뢰도. 타입드 경로가 더 멀리 점수를 나른다.
                        double childW = w * HOP_STEP * ((double) wEdge / maxW) * typeConfidence(dom);
                        hopOf.putIfAbsent(nb, hop + 1);
                        if (childW > entWeight.getOrDefault(nb, -1.0)) {   // 최강 경로로 부모/타입 갱신
                            entWeight.put(nb, childW);
                            parentOf.put(nb, e);
                            edgeTypeOf.put(nb, dom);
                        }
                        frontier.add(nb);
                    }
                }
            }
        }

        return artScore.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(topK)
                .map(en -> {
                    Article a = g.articles.get(en.getKey());
                    if (a == null) return null;
                    List<PathStep> path = reconstruct(bestEntForArt.get(en.getKey()), parentOf, edgeTypeOf);
                    return new GraphHit(a, en.getValue(), path);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /** 이웃 엣지의 총 공출현 횟수(모든 타입 합). */
    private static int edgeWeight(Map<EdgeType, Integer> typeCounts) {
        int s = 0;
        for (int v : typeCounts.values()) s += v;
        return s;
    }

    /** 이웃 엣지의 대표 타입(최다 카운트). 보통 한 쌍은 단일 타입이라 그 타입. */
    private static EdgeType dominantType(Map<EdgeType, Integer> typeCounts) {
        EdgeType dom = EdgeType.UNTYPED;
        int best = -1;
        for (Map.Entry<EdgeType, Integer> te : typeCounts.entrySet()) {
            if (te.getValue() > best) { best = te.getValue(); dom = te.getKey(); }
        }
        return dom;
    }

    /** 도달 엔티티에서 시드까지 부모를 거슬러 경로를 복원한다. 시드(직접 매칭)면 빈 경로. */
    private List<PathStep> reconstruct(String entity, Map<String, String> parentOf, Map<String, EdgeType> edgeTypeOf) {
        LinkedList<PathStep> steps = new LinkedList<>();
        String cur = entity;
        int guard = 0;
        while (cur != null && parentOf.containsKey(cur) && guard++ < MAX_HOP + 1) {
            String p = parentOf.get(cur);
            steps.addFirst(new PathStep(p, edgeTypeOf.get(cur), cur));
            cur = p;
        }
        return steps;
    }

    public synchronized Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        int nodes = 0, edges = 0, arts = 0;
        for (NsGraph g : namespaces.values()) {
            nodes += g.entityArticles.size();
            arts += g.articles.size();
            for (Map<String, Map<EdgeType, Integer>> m : g.adjacency.values()) edges += m.size();
        }
        out.put("namespaces", namespaces.size());
        out.put("entities", nodes);
        out.put("edges", edges / 2);   // 무방향(양방향 저장) 보정
        out.put("articles", arts);
        return out;
    }

    /**
     * namespace 1개의 그래프 통계 + 엣지 타입별 분포. ingest가 그래프까지 제대로 됐는지,
     * 그리고 타입드 엣지(시드 관계)가 실제로 생성됐는지 운영/검증용으로 노출한다.
     */
    public synchronized Map<String, Object> stats(String namespace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("namespace", nsKey(namespace));
        NsGraph g = namespaces.get(nsKey(namespace));
        if (g == null) {
            out.put("entities", 0);
            out.put("edges", 0);
            out.put("articles", 0);
            out.put("edgeTypes", Map.of());
            return out;
        }
        int directedNeighbors = 0;
        Map<EdgeType, Integer> typeDirected = new EnumMap<>(EdgeType.class);
        for (Map<String, Map<EdgeType, Integer>> nbrs : g.adjacency.values()) {
            directedNeighbors += nbrs.size();
            for (Map<EdgeType, Integer> tc : nbrs.values()) {
                for (EdgeType t : tc.keySet()) typeDirected.merge(t, 1, Integer::sum);
            }
        }
        Map<String, Integer> edgeTypes = new LinkedHashMap<>();
        for (Map.Entry<EdgeType, Integer> e : typeDirected.entrySet()) {
            edgeTypes.put(e.getKey().name(), e.getValue() / 2);   // 무방향 보정
        }
        out.put("entities", g.entityArticles.size());
        out.put("edges", directedNeighbors / 2);
        out.put("articles", g.articles.size());
        out.put("edgeTypes", edgeTypes);
        return out;
    }

    private static String nsKey(String ns) {
        return (ns == null || ns.isBlank()) ? "default" : ns.trim();
    }
}
