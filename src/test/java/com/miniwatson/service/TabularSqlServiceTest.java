package com.miniwatson.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * requireReadOnly: SELECT/WITH 전용 가드. 쓰기/DDL 키워드를 막아 텍스트-to-SQL 경로의
 * 사고성 데이터 변경을 차단한다. DuckDB 연결이 필요한 runSelect 대신 가드만 순수 테스트.
 */
class TabularSqlServiceTest {

    @Test
    void allowsSelect() {
        assertDoesNotThrow(() -> TabularSqlService.requireReadOnly("SELECT * FROM t"));
    }

    @Test
    void allowsWith() {
        assertDoesNotThrow(() ->
                TabularSqlService.requireReadOnly("WITH x AS (SELECT 1) SELECT * FROM x"));
    }

    @Test
    void isCaseInsensitive() {
        assertDoesNotThrow(() -> TabularSqlService.requireReadOnly("select * from t"));
    }

    @Test
    void allowsLeadingAndTrailingWhitespace() {
        assertDoesNotThrow(() -> TabularSqlService.requireReadOnly("  SELECT 1  "));
    }

    @Test
    void rejectsDrop() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("DROP TABLE t"));
    }

    @Test
    void rejectsDelete() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("DELETE FROM t"));
    }

    @Test
    void rejectsUpdate() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("UPDATE t SET a = 1"));
    }

    @Test
    void rejectsInsert() {
        assertThrows(IllegalArgumentException.class,
                () -> TabularSqlService.requireReadOnly("INSERT INTO t VALUES (1)"));
    }
}
