package com.miniwatson.data;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * pgvector 리터럴 직렬화/파싱 회귀 방지. DB 없이 순수 함수만 검증
 * (TabularSqlService.requireReadOnly와 같은 패턴). 이 변환이 틀어지면
 * 적재/검색이 조용히 깨지거나 MMR이 잘못된 임베딩으로 rerank한다.
 */
class PgVectorStoreTest {

    @Test
    void serializesVectorToBracketLiteral() {
        assertEquals("[0.1,-0.2,3.5]",
                PgVectorStore.toVectorLiteral(List.of(0.1f, -0.2f, 3.5f)));
    }

    @Test
    void roundTripsLiteralBackToFloats() {
        List<Float> v = List.of(0.1f, -0.2f, 0.0f, 768.0f);
        List<Float> back = PgVectorStore.parseVector(PgVectorStore.toVectorLiteral(v));
        assertEquals(v.size(), back.size());
        for (int i = 0; i < v.size(); i++) assertEquals(v.get(i), back.get(i), 1e-6f);
    }

    @Test
    void parsesEmptyVectorLiteral() {
        assertTrue(PgVectorStore.parseVector("[]").isEmpty());
    }

    @Test
    void parseToleratesNullOrTooShort() {
        assertTrue(PgVectorStore.parseVector(null).isEmpty());
        assertTrue(PgVectorStore.parseVector("").isEmpty());
    }
}
