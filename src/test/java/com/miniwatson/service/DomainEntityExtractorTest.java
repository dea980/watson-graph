package com.miniwatson.service;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 도메인 엔티티 추출(사전+규칙+약어정규화) 및 질의 재작성 검증. */
class DomainEntityExtractorTest {

    @Test
    void extractsDictionaryRuleAndNormalizesAcronyms() {
        String t = "금융위원회는 자본시장법 시행령 개정으로 ETF 상장 요건을 정비했다. FSS는 전자공시를 점검한다.";
        Set<String> ents = DomainEntityExtractor.extract(t);

        assertTrue(ents.contains("금융위원회"));          // 사전(기관)
        assertTrue(ents.contains("자본시장법"));            // 규칙: 시행령 → 모법으로 접힘
        assertFalse(ents.contains("자본시장법 시행령"));
        assertTrue(ents.contains("상장지수펀드"));          // 약어 ETF → 정식명 정규화
        assertTrue(ents.contains("금융감독원"));            // 약어 FSS → 정식명 정규화
    }

    @Test
    void queryRewriteAddsAcronymAndFullName() {
        // 정식명만 있는 질의 → 약어 보강
        String r1 = QueryRewriter.rewriteForRetrieval("금융감독원 공시");
        assertTrue(r1.contains("FSS"));
        // 약어만 있는 질의 → 정식명 보강
        String r2 = QueryRewriter.rewriteForRetrieval("ETF 상장 요건");
        assertTrue(r2.contains("상장지수펀드"));
    }

    @Test
    void noDomainTermsLeavesQueryUnchanged() {
        assertEquals("점심 메뉴 추천", QueryRewriter.rewriteForRetrieval("점심 메뉴 추천"));
    }
}
