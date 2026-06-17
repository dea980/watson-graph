package com.miniwatson.service;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 도메인 인지 질의 재작성 (retriever 앞단, LLM 0회).
 *
 * 왜: 사용자는 "금감원 공시"라고 묻는데 문서엔 "금융감독원 / FSS / 전자공시시스템"으로 적혀 있으면
 * 벡터/키워드 모두 어휘 간극으로 retrieval이 깨진다. 질문에 도메인 약어<->정식명을 양방향 보강해
 * 같은 어휘 공간으로 끌어온다. 질문당 1회 문자열 처리 — 로컬 부담 없음.
 *
 * 생성(LLM)에 보여줄 질문은 그대로 두고, "검색용 질의"만 확장한다(원문 의미 보존).
 *
 * 순수 static — standalone 검증 가능.
 */
public final class QueryRewriter {

    private QueryRewriter() {}

    /** 검색용으로 확장된 질의를 반환. 원문 + (약어<->정식명) 보강어. */
    public static String rewriteForRetrieval(String query) {
        if (query == null || query.isBlank()) return query;

        StringBuilder extra = new StringBuilder();
        for (Map.Entry<String, String> e : DomainGlossary.ACRONYMS.entrySet()) {
            String acro = e.getKey(), full = e.getValue();
            boolean hasAcro = containsWord(query, acro);
            boolean hasFull = query.contains(full);
            if (hasAcro && !hasFull) append(extra, full);   // 약어만 → 정식명 보강
            if (hasFull && !hasAcro) append(extra, acro);   // 정식명만 → 약어 보강
        }
        return extra.length() == 0 ? query : query + " " + extra;
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(s);
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }
}
