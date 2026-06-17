package com.miniwatson.data;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-memory vector index for sub-linear approximate kNN retrieval.
 *
 * <p>Two retrieval modes:
 * <ul>
 *   <li><b>brute-force</b> — exact cosine over every vector in the namespace.
 *       O(n) per query, but already a big win over the old path because vectors
 *       live in RAM (no Parquet re-read on every question).</li>
 *   <li><b>LSH</b> (default) — random-hyperplane Locality-Sensitive Hashing.
 *       Each vector gets an H-bit signature (sign of its dot product with H
 *       random hyperplanes). Similar vectors collide in the same / nearby
 *       buckets, so a query only exact-scores a small candidate set instead of
 *       the whole corpus. Average cost is sub-linear; correctness is preserved
 *       by an exact-cosine fallback when a bucket is too sparse.</li>
 * </ul>
 *
 * <p>The index is the in-memory source of truth for retrieval. It is hydrated
 * from Parquet at startup and updated incrementally on each ingest, partitioned
 * per {@code namespace} so tenants never see each other's vectors.
 */
@Component
// vector.store=memory(기본) 일 때만 활성. pgvector 선택 시 PgVectorStore가 대신 뜬다(빈 충돌 방지).
@ConditionalOnProperty(name = "vector.store", havingValue = "memory", matchIfMissing = true)
public class VectorIndex implements VectorStore{

    private static final Logger log = LoggerFactory.getLogger(VectorIndex.class);
    private static final long SEED = 42L; // deterministic hyperplanes → reproducible buckets

    // private final ArticleParquetStore store;

    private final ArticleRepository store;

    @Value("${vector.index.lsh.enabled:true}")
    private boolean lshEnabled;

    @Value("${vector.index.lsh.hyperplanes:16}")
    private int numHyperplanes;

    @Value("${vector.index.lsh.probe-radius:1}")
    private int probeRadius; // hamming radius of buckets to probe (capped at 2)

    /** namespace -> index */
    private final Map<String, NamespaceIndex> namespaces = new HashMap<>();

    /** Lazily built once the embedding dimension is known. hyperplanes[h][dim]. */
    private float[][] hyperplanes;
    private int dim = -1;

    public VectorIndex(ArticleRepository store) {
        this.store = store;
    }

    @PostConstruct
    public synchronized void hydrate() {
        try {
            List<Article> all = store.loadAll();
            rebuild(all);
            log.info("[VectorIndex] hydrated: {} vectors across {} namespace(s), mode={}",
                    all.size(), namespaces.size(), lshEnabled ? "LSH(H=" + numHyperplanes + ")" : "brute-force");
        } catch (IOException e) {
            log.warn("[VectorIndex] hydrate failed, starting empty: {}", e.getMessage());
        }
    }

    public synchronized void rebuild(List<Article> articles) {
        namespaces.clear();
        for (Article a : articles) {
            add(a);
        }
    }

    /** Incrementally add one article (no-op if it has no embedding). */
    public synchronized void add(Article article) {
        if (article.getEmbedding() == null || article.getEmbedding().isEmpty()) {
            return;
        }
        float[] vec = toArray(article.getEmbedding());
        ensureHyperplanes(vec.length);

        String ns = nsKey(article.getNamespace());
        NamespaceIndex idx = namespaces.computeIfAbsent(ns, k -> new NamespaceIndex());

        Entry entry = new Entry(article, vec, norm(vec));
        int position = idx.entries.size();
        idx.entries.add(entry);
        if (lshEnabled && hyperplanes != null) {
            int sig = signature(vec);
            idx.buckets.computeIfAbsent(sig, k -> new ArrayList<>()).add(position);
        }
    }

    /**
     * Return the top-K articles in {@code namespace} most similar to {@code query}.
     * Uses LSH candidate generation when enabled, else exact scan. Always falls
     * back to an exact namespace scan if LSH yields fewer than K candidates.
     */
    public synchronized List<Article> search(String namespace, List<Float> query, int topK) {
        NamespaceIndex idx = namespaces.get(nsKey(namespace));
        if (idx == null || idx.entries.isEmpty()) {
            return List.of();
        }
        float[] q = toArray(query);
        double qNorm = norm(q);

        List<Entry> candidates;
        if (lshEnabled && hyperplanes != null) {
            candidates = lshCandidates(idx, q);
            if (candidates.size() < topK) {
                candidates = idx.entries; // sparse bucket → exact fallback
            }
        } else {
            candidates = idx.entries;
        }

        return candidates.stream()
                .sorted(Comparator.comparingDouble(e -> -cosine(q, qNorm, e)))
                .limit(topK)
                .map(e -> e.article)
                .collect(Collectors.toList());
    }

    /** Per-namespace diagnostics for the dashboard / API. */
    public synchronized Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", lshEnabled ? "lsh" : "brute-force");
        out.put("hyperplanes", lshEnabled ? numHyperplanes : 0);
        out.put("probeRadius", lshEnabled ? Math.min(probeRadius, 2) : 0);
        out.put("dimension", dim);

        Map<String, Object> perNs = new LinkedHashMap<>();
        for (Map.Entry<String, NamespaceIndex> e : namespaces.entrySet()) {
            Map<String, Object> ns = new LinkedHashMap<>();
            ns.put("vectors", e.getValue().entries.size());
            ns.put("buckets", e.getValue().buckets.size());
            perNs.put(e.getKey(), ns);
        }
        out.put("namespaces", perNs);
        return out;
    }

    // ----------------------------------------------------------------- LSH internals

    private List<Entry> lshCandidates(NamespaceIndex idx, float[] q) {
        int sig = signature(q);
        Set<Integer> seen = new LinkedHashSet<>();
        List<Entry> candidates = new ArrayList<>();
        for (int probe : neighborSignatures(sig, Math.min(probeRadius, 2))) {
            List<Integer> bucket = idx.buckets.get(probe);
            if (bucket == null) continue;
            for (int pos : bucket) {
                if (seen.add(pos)) {
                    candidates.add(idx.entries.get(pos));
                }
            }
        }
        return candidates;
    }

    /** All signatures within hamming distance {@code radius} (radius capped at 2). */
    private List<Integer> neighborSignatures(int sig, int radius) {
        List<Integer> res = new ArrayList<>();
        res.add(sig);
        if (radius >= 1) {
            for (int i = 0; i < numHyperplanes; i++) {
                res.add(sig ^ (1 << i));
            }
        }
        if (radius >= 2) {
            for (int i = 0; i < numHyperplanes; i++) {
                for (int j = i + 1; j < numHyperplanes; j++) {
                    res.add(sig ^ (1 << i) ^ (1 << j));
                }
            }
        }
        return res;
    }

    private int signature(float[] vec) {
        int sig = 0;
        for (int h = 0; h < numHyperplanes; h++) {
            if (dot(vec, hyperplanes[h]) >= 0) {
                sig |= (1 << h);
            }
        }
        return sig;
    }

    private synchronized void ensureHyperplanes(int dimension) {
        if (hyperplanes != null) return;
        this.dim = dimension;
        int h = Math.min(numHyperplanes, 31); // sign-bit budget of an int signature
        this.numHyperplanes = h;
        Random rnd = new Random(SEED);
        hyperplanes = new float[h][dimension];
        for (int i = 0; i < h; i++) {
            for (int d = 0; d < dimension; d++) {
                hyperplanes[i][d] = (float) rnd.nextGaussian();
            }
        }
    }

    // ----------------------------------------------------------------- math

    private double cosine(float[] q, double qNorm, Entry e) {
        double denom = qNorm * e.norm;
        if (denom == 0) return 0;
        return dot(q, e.vec) / denom;
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }

    private static double norm(float[] v) {
        double s = 0;
        for (float x : v) s += x * x;
        return Math.sqrt(s);
    }

    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static String nsKey(String ns) {
        return (ns == null || ns.isBlank()) ? "default" : ns;
    }

    // ----------------------------------------------------------------- containers

    private static final class NamespaceIndex {
        final List<Entry> entries = new ArrayList<>();
        final Map<Integer, List<Integer>> buckets = new HashMap<>();
    }

    private static final class Entry {
        final Article article;
        final float[] vec;
        final double norm;

        Entry(Article article, float[] vec, double norm) {
            this.article = article;
            this.vec = vec;
            this.norm = norm;
        }
    }
}
