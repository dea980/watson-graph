package com.miniwatson.service;

import com.miniwatson.data.Article;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component("llm")
public class LlmReranker implements Reranker {

    private final OllamaService ollamaService;

    public LlmReranker(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @Override
    public List<Article> rerank(String question, List<Article> candidates, int topK) {
        if (candidates.size() <= topK) return candidates;

        // 후보를 번호 매겨 나열 (각 300자로 제한해 프롬프트 폭발 방지)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            String s = candidates.get(i).getSummary();
            if (s.length() > 300) s = s.substring(0, 300);
            sb.append("[").append(i).append("] ").append(s).append("\n");
        }

        String prompt = "You are a passage reranker. Question: \"" + question + "\"\n\n"
                + "Passages:\n" + sb
                + "\nReturn ONLY the " + topK + " most relevant passage numbers, "
                + "most relevant first, comma-separated (e.g. 3,0). No other text.";

        String resp = ollamaService.ask(prompt, null, "rerank: " + question);

        // 응답에서 번호 추출 → 순서 유지, 유효 범위만
        List<Article> out = new ArrayList<>();
        Set<Integer> used = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\d+").matcher(resp);
        while (m.find() && out.size() < topK) {
            int idx = Integer.parseInt(m.group());
            if (idx >= 0 && idx < candidates.size() && used.add(idx)) {
                out.add(candidates.get(idx));
            }
        }
        // LLM이 이상하게 답하면 상위 topK로 폴백
        if (out.isEmpty()) return candidates.subList(0, topK);
        return out;
    }
}