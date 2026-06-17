package com.miniwatson.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 약어/정식명 불일치 보정. 문서에서 "Chief AI Officer (CAIO)" 같은 정의를 찾아
 * 약어->정식명 맵을 만들고, 청크에 약어만 있고 정식명이 없으면 정식명을 주입한다.
 *
 * 왜: 질의는 정식명("Chief AI Officer")으로 묻는데 정답 청크엔 약어("CAIO")만 있으면
 * 같은 의미인데 임베딩이 안 붙어 retrieval이 깨진다(ceo-caio-adoption). 정식명을 청크에
 * 넣어 같은 어휘 공간으로 끌어온다. LLM 0회, 순수 문자열 — recursive처럼 비용 0.
 *
 * 순수 static이라 Spring 없이 단위 테스트 가능(EmbeddingService.prefixFor와 같은 패턴).
 */
public final class AcronymExpander {

    private AcronymExpander() {}

    // "Full Name (ACRO)": 정식명 1~5단어(각 대문자 시작) + 괄호 안 2~6 대문자
    private static final Pattern DEF =
        Pattern.compile("([A-Z][A-Za-z0-9]*(?:\\s+[A-Z][A-Za-z0-9]*){0,4})\\s*\\(([A-Z]{2,6})\\)");

    /** 문서 전체에서 약어 정의 수집. 약어 첫 글자 == 정식명 첫 글자일 때만 채택(오탐 감소). */
    public static Map<String, String> buildGlossary(String fullText) {
        Map<String, String> g = new LinkedHashMap<>();
        if (fullText == null) return g;
        Matcher m = DEF.matcher(fullText);
        while (m.find()) {
            String full = m.group(1).trim();
            String acro = m.group(2);
            if (full.isEmpty()) continue;
            if (Character.toUpperCase(acro.charAt(0)) == Character.toUpperCase(full.charAt(0))) {
                g.putIfAbsent(acro, full);
            }
        }
        return g;
    }

    /** 청크에 약어(단어 경계)는 있고 정식명이 없으면 "정식명 (약어)"를 꼬리에 덧붙인다. */
    public static String expand(String chunk, Map<String, String> glossary) {
        if (chunk == null || glossary == null || glossary.isEmpty()) return chunk;
        StringBuilder adds = new StringBuilder();
        for (Map.Entry<String, String> e : glossary.entrySet()) {
            String acro = e.getKey(), full = e.getValue();
            if (containsWord(chunk, acro) && !chunk.contains(full)) {
                if (adds.length() > 0) adds.append("; ");
                adds.append(full).append(" (").append(acro).append(")");
            }
        }
        return adds.length() == 0 ? chunk : chunk + "\n" + adds;
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }
}
