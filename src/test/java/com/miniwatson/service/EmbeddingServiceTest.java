package com.miniwatson.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * prefixFor: 모델별 prefix 규약(공정 비교의 정확성 핵심)을 고정한다.
 * 이 매핑이 틀어지면 에러 없이 검색 점수만 조용히 깎인다.
 */
class EmbeddingServiceTest {

    @Test
    void nomicUsesQueryAndDocumentPrefixes() {
        var p = EmbeddingService.prefixFor("nomic-embed-text");
        assertEquals("search_query: ", p.q());
        assertEquals("search_document: ", p.d());
    }

    @Test
    void graniteEmbeddingUsesNoPrefix() {
        var p = EmbeddingService.prefixFor("granite-embedding:278m");
        assertEquals("", p.q());
        assertEquals("", p.d());
    }

    @Test
    void mxbaiAddsInstructionToQueryOnly() {
        var p = EmbeddingService.prefixFor("mxbai-embed-large");
        assertTrue(p.q().startsWith("Represent this sentence"));
        assertEquals("", p.d());   // 저장 청크엔 prefix 없음
    }

    @Test
    void unknownModelFallsBackToNoPrefix() {
        var p = EmbeddingService.prefixFor("some-future-model");
        assertEquals("", p.q());
        assertEquals("", p.d());
    }

    @Test
    void isCaseInsensitiveAndNullSafe() {
        assertEquals("search_query: ", EmbeddingService.prefixFor("NOMIC-embed-text").q());
        assertEquals("", EmbeddingService.prefixFor(null).q());   // NPE 안 남
    }
}
