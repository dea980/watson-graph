package com.miniwatson.service;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 약어 정의 수집 + 청크 주입의 회귀 방지. ceo-caio-adoption 케이스 모사:
 * 정식명은 다른 청크에, 숫자 청크엔 약어만 있는 상황.
 */
class AcronymExpanderTest {

    @Test
    void buildsGlossaryFromDefinitionPattern() {
        var g = AcronymExpander.buildGlossary("the Chief AI Officer (CAIO) leads AI strategy.");
        assertEquals("Chief AI Officer", g.get("CAIO"));
    }

    @Test
    void rejectsMismatchedInitial() {
        // 첫 글자 불일치(X != Foo)면 오탐으로 보고 버린다
        var g = AcronymExpander.buildGlossary("Foo Bar (XYZ) something");
        assertFalse(g.containsKey("XYZ"));
    }

    @Test
    void injectsFullNameWhenChunkHasOnlyAcronym() {
        var g = Map.of("CAIO", "Chief AI Officer");
        String out = AcronymExpander.expand("In 2026, 76% have a CAIO in place.", g);
        assertTrue(out.contains("Chief AI Officer"), "정식명이 주입돼야 함");
    }

    @Test
    void doesNotDuplicateWhenFullNameAlreadyPresent() {
        var g = Map.of("CAIO", "Chief AI Officer");
        String chunk = "The Chief AI Officer (CAIO) role grew.";
        assertEquals(chunk, AcronymExpander.expand(chunk, g));   // 이미 있으면 그대로
    }

    @Test
    void ignoresAcronymSubstringsViaWordBoundary() {
        var g = Map.of("AI", "Artificial Intelligence");
        // "CAIO" 안의 "AI"는 단어 경계가 아니므로 매칭 안 됨
        assertEquals("Only CAIO here.", AcronymExpander.expand("Only CAIO here.", g));
    }

    @Test
    void emptyGlossaryReturnsChunkUnchanged() {
        assertEquals("nothing to do", AcronymExpander.expand("nothing to do", Map.of()));
    }
}
