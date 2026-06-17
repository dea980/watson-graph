package com.miniwatson.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * cleanSql: LLM 응답에서 실행 가능한 SQL만 남기는 정제 로직.
 * 코드펜스/설명문/끝 세미콜론을 제거하지 못하면 DuckDB 실행이 파싱 에러로 깨진다.
 * 순수 static 함수라 연결 없이 단위 테스트.
 */
class TextToSqlServiceTest {

    @Test
    void stripsSqlCodeFence() {
        assertEquals("SELECT 1", TextToSqlService.cleanSql("```sql\nSELECT 1\n```"));
    }

    @Test
    void takesFromSelectKeyword() {
        assertEquals("SELECT a FROM t",
                TextToSqlService.cleanSql("Here is the query: SELECT a FROM t"));
    }

    @Test
    void keepsWithCte() {
        String in = "WITH x AS (SELECT 1) SELECT * FROM x";
        assertEquals(in, TextToSqlService.cleanSql(in));
    }

    @Test
    void dropsTrailingSemicolon() {
        assertEquals("SELECT 1", TextToSqlService.cleanSql("SELECT 1;"));
    }

    @Test
    void handlesInputWithoutSelectOrWith() {
        // SELECT/WITH가 없으면 strip 수준만 — 던지지 않는 것이 핵심.
        assertDoesNotThrow(() -> TextToSqlService.cleanSql("  no sql here  "));
    }
}
