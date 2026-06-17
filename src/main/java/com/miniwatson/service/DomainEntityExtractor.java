package com.miniwatson.service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 도메인 엔티티 추출 (LLM 0회).
 *
 * 왜: 로컬 GraphRAG에서 LLM triple 추출은 인덱싱 비용이 폭발한다. 대신
 *   (1) 도메인 사전(DomainGlossary) 매칭,
 *   (2) 약어 -> 정식명 정규화,
 *   (3) 규칙(법령 "○○법" 패턴) 추출
 * 로 결정적이고 빠르게 엔티티를 뽑는다. 이게 co-occurrence 그래프의 노드가 된다.
 *
 * 순수 static — Spring 불필요, standalone 검증 가능.
 */
public final class DomainEntityExtractor {

    private DomainEntityExtractor() {}

    // "○○법", "○○법 시행령", "○○법 시행규칙" — 한글 2자 이상 + 법(+시행령/시행규칙)
    // (?![인률]): "법인"/"법률"의 '법'을 법령으로 오인하지 않도록 차단
    private static final Pattern LAW_PAT =
        Pattern.compile("[가-힣]{2,}법(?![인률])(?:\\s*시행령|\\s*시행규칙)?");

    // 단어 경계 약어 (영문/숫자/하이픈). 예: ETF, K-IFRS, ROE
    private static final Pattern ACRO_PAT =
        Pattern.compile("\\b[A-Z][A-Z0-9-]{1,7}\\b");

    /**
     * 텍스트에서 도메인 엔티티(정규화된 정식명) 집합을 추출한다.
     * - 약어는 정식명으로 치환해 같은 노드로 합친다(ETF == 상장지수펀드).
     * - 사전 엔티티는 부분 문자열 매칭, 법령은 사전 + 규칙 매칭.
     */
    public static Set<String> extract(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return out;

        // 1) 사전 엔티티 (기관/법령/상품) 부분 문자열 매칭
        for (String e : DomainGlossary.allEntities()) {
            if (text.contains(e)) out.add(e);
        }

        // 2) 약어 -> 정식명 정규화
        Matcher am = ACRO_PAT.matcher(text);
        while (am.find()) {
            String acro = am.group();
            String full = DomainGlossary.ACRONYMS.get(acro);
            if (full != null) out.add(full);            // 정식명을 노드로
        }

        // 3) 법령 규칙 추출 (사전에 없는 ○○법도 포착)
        Matcher lm = LAW_PAT.matcher(text);
        while (lm.find()) {
            String law = lm.group().replaceAll("\\s+", " ").trim();
            // "시행령/시행규칙"은 모법으로 접어 노드 폭발 방지
            String base = law.replaceAll("\\s*(시행령|시행규칙)$", "");
            out.add(base);
        }

        return out;
    }

    /** ACRO 약어를 정식명으로 바꾼 맵(쿼리 재작성에서 재사용). */
    public static Map<String, String> acronymMap() {
        return DomainGlossary.ACRONYMS;
    }
}
