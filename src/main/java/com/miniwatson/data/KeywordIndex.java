package com.miniwatson.data;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/** 인메모리 BM25 역색인. VectorIndex의 어휘(lexical) 짝. namespace별 분리. */
@Component
public class KeywordIndex {

    private static final Logger log = LoggerFactory.getLogger(KeywordIndex.class);
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final ArticleRepository store;
    private final Map<String, NsIndex> namespaces = new HashMap<>();

    public KeywordIndex(ArticleRepository store) {
        this.store = store;
    }

    private static class NsIndex {
        List<Article> docs = new ArrayList<>();
        List<List<String>> docTokens = new ArrayList<>();
        Map<String, Integer> df = new HashMap<>();
        double avgdl = 0;
    }

    @PostConstruct
    public synchronized void hydrate() {
        try {
            rebuild(store.loadAll());
            log.info("[KeywordIndex] hydrated {} namespace(s)", namespaces.size());
        } catch (IOException e) {
            log.warn("[KeywordIndex] hydrate failed: {}", e.getMessage());
        }
    }

    public synchronized void rebuild(List<Article> articles) {
        namespaces.clear();
        for (Article a : articles) add(a);
    }

    public synchronized void add(Article a) {
        String ns = nsKey(a.getNamespace());
        NsIndex idx = namespaces.computeIfAbsent(ns, k -> new NsIndex());
        List<String> tokens = tokenize(a.getSummary());
        idx.docs.add(a);
        idx.docTokens.add(tokens);
        for (String t : new HashSet<>(tokens)) idx.df.merge(t, 1, Integer::sum);
        idx.avgdl = idx.docTokens.stream().mapToInt(List::size).average().orElse(0);
    }

    public synchronized List<Article> search(String namespace, String query, int topK) {
        NsIndex idx = namespaces.get(nsKey(namespace));
        if (idx == null || idx.docs.isEmpty()) return List.of();

        List<String> qTerms = tokenize(query);
        int N = idx.docs.size();
        double[] scores = new double[N];

        for (int i = 0; i < N; i++) {
            List<String> doc = idx.docTokens.get(i);
            Map<String, Long> tf = termFreq(doc);
            double s = 0;
            for (String t : qTerms) {
                int dfi = idx.df.getOrDefault(t, 0);
                if (dfi == 0) continue;
                long f = tf.getOrDefault(t, 0L);
                if (f == 0) continue;
                double idf = Math.log(1 + (N - dfi + 0.5) / (dfi + 0.5));
                double denom = f + K1 * (1 - B + B * doc.size() / idx.avgdl);
                s += idf * (f * (K1 + 1)) / denom;
            }
            scores[i] = s;
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < N; i++) if (scores[i] > 0) order.add(i);
        order.sort((x, y) -> Double.compare(scores[y], scores[x]));

        List<Article> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, order.size()); i++) out.add(idx.docs.get(order.get(i)));
        return out;
    }

    private List<String> tokenize(String text) {
        if (text == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : text.toLowerCase().split("[^a-z0-9]+")) if (!t.isBlank()) out.add(t);
        return out;
    }

    private Map<String, Long> termFreq(List<String> tokens) {
        Map<String, Long> tf = new HashMap<>();
        for (String t : tokens) tf.merge(t, 1L, Long::sum);
        return tf;
    }

    private String nsKey(String ns) {
        return (ns == null || ns.isBlank()) ? "default" : ns;
    }
}