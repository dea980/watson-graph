package com.miniwatson.service;


import org.springframework.stereotype.Component;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

@Component("semantic")
public class SemanticChunker implements Chunker{

    private final EmbeddingService embeddingService;

    private float[] toArray(List<Float> v) {
        float[] a = new float[v.size()];
        for (int i = 0; i < v.size(); i++) a[i] = v.get(i);
        return a;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private double percentile(List<Double> values, double p) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int idx = (int) Math.floor((p / 100.0) * (sorted.size() - 1));
        return sorted.get(idx);
    }

    public SemanticChunker(EmbeddingService embeddingService){
        this.embeddingService = embeddingService;
    }

    @Override
    public List<String> chunk(String text, int maxSize){
        // 문장 분리 (간단히 마침표/줄 바꿈 기준)
        String[] sentences = text.split("(?<=[.!?])\\s+|\\n{2,}");
        if (sentences.length <= 1)
            return List.of(text.trim());

        // 각문장 임베딩
        List<float[]> embs = new ArrayList<>();
        for (String s: sentences){
            embs.add(toArray(embeddingService.embedDocument(s)));

        }

        // 인접 유사도 -> 평균 이하로 떨어지면 경계
        List<Double> sims = new ArrayList<>();
        for (int i = 0; i < embs.size() - 1; i++) {
            sims.add(cosine(embs.get(i), embs.get(i + 1)));
        }
        double threshold = percentile(sims, 25);  // 하위 25% 지점에서 끊기

        // 4) 경계 기준으로 문장 묶기 (+ maxSize 캡)
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder(sentences[0]);
        for (int i = 0; i < sims.size(); i++) {
            boolean breakHere = sims.get(i) < threshold
                    || cur.length() + sentences[i + 1].length() > maxSize;
            if (breakHere) {
                chunks.add(cur.toString().trim());
                cur = new StringBuilder();
            } else {
                cur.append(" ");
            }
            cur.append(sentences[i + 1]);
        }
        if (cur.length() > 0) chunks.add(cur.toString().trim());
        return chunks;

    }
}
