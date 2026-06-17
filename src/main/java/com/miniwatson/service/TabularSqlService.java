package com.miniwatson.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * watsonx.data 라이크하우스 패턴: 표 파일(CSV)을 DuckDB(임베디드 컬럼 엔진)로 그 자리에서 질의한다.
 * 파일을 옮기지 않고 read_csv_auto로 테이블화 — zero-ETL. 실행은 SELECT 전용.
 *
 * 왜 DuckDB: 컬럼형 OLAP라 표 스캔/집계가 본업이고, CSV/Parquet을 네이티브로 읽으며,
 * 임베디드라 모든 프로필(dev/demo/prod)에서 컨테이너 없이 동작한다.
 */
@Service
public class TabularSqlService {

    private Connection conn;

    @PostConstruct
    void init() throws SQLException {
        conn = DriverManager.getConnection("jdbc:duckdb:");   // in-memory (드라이버는 JDBC SPI로 자동 등록)
    }

    @PreDestroy
    void close() throws SQLException {
        if (conn != null) conn.close();
    }

    /** CSV를 테이블로 등록 (zero-ETL: read_csv_auto). 테이블명은 영숫자/언더스코어만. */
    public synchronized void registerCsv(String table, String path) throws SQLException {
        String t = safeIdent(table);
        String p = path.replace("'", "''");   // 경로 문자열 escape
        try (Statement st = conn.createStatement()) {
            // normalize_names=true: "Orbiting Body" -> orbiting_body 등 공백·특수문자 제거.
            // LLM이 공백 컬럼을 인용하지 못해 SQL이 깨지는 문제를 적재 단계에서 원천 차단.
            st.execute("CREATE OR REPLACE TABLE " + t +
                       " AS SELECT * FROM read_csv_auto('" + p + "', normalize_names=true)");
        }
    }

    /** 컬럼명 + 타입 — LLM에 줄 스키마 컨텍스트. */
    public String schema(String table) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("DESCRIBE " + safeIdent(table))) {
            while (rs.next()) {
                sb.append(rs.getString("column_name")).append(" ")
                  .append(rs.getString("column_type")).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * xlsx를 등록 (POI). 정부/기업 양식은 위에 제목·안내행이 깔려 진짜 헤더가 N행이므로
     * headerRow부터 읽어 임시 CSV로 변환한 뒤 registerCsv로 합류(normalize_names 재사용).
     * DuckDB excel 확장(네트워크) 대신 POI로 오프라인 처리.
     */
    public synchronized void registerXlsx(String table, String path, int headerRow) throws Exception {
        java.io.File tmp = java.io.File.createTempFile("xlsx-", ".csv");
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(java.nio.file.Files.newInputStream(java.nio.file.Path.of(path)));
             var w = new java.io.PrintWriter(tmp, java.nio.charset.StandardCharsets.UTF_8)) {
            var sheet = wb.getSheetAt(0);
            var fmt = new org.apache.poi.ss.usermodel.DataFormatter();
            var head = sheet.getRow(headerRow);
            if (head == null) throw new IllegalArgumentException("headerRow " + headerRow + " 가 비어있음");
            int ncol = head.getLastCellNum();
            int last = sheet.getLastRowNum();
            for (int r = headerRow; r <= last; r++) {
                var row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                boolean allBlank = true;
                for (int c = 0; c < ncol; c++) {
                    var cell = (row == null) ? null : row.getCell(c);
                    String v = (cell == null) ? "" : fmt.formatCellValue(cell).trim();
                    if (!v.isEmpty()) allBlank = false;
                    cells.add(csvEscape(v));
                }
                if (r > headerRow && allBlank) continue;   // 빈 데이터 행 스킵
                w.println(String.join(",", cells));
            }
        }
        registerCsv(table, tmp.getAbsolutePath());   // 동기 실행 — 등록 후 임시파일 삭제 안전
        tmp.delete();
    }

    private String csvEscape(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** 샘플 행 n개 — LLM이 실제 값(예: quarter=Q3)을 보고 올바른 리터럴을 쓰게. */
    public String sample(String table, int n) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + safeIdent(table) + " LIMIT " + Math.max(1, n))) {
            ResultSetMetaData md = rs.getMetaData();
            int c = md.getColumnCount();
            while (rs.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= c; i++) cells.add(md.getColumnName(i) + "=" + rs.getObject(i));
                sb.append(String.join(", ", cells)).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * SELECT/WITH로 시작 + 위험 키워드 차단을 강제. 위반 시 IllegalArgumentException.
     * 순수 함수로 분리해 DuckDB 연결 없이 단위 테스트 가능(읽기 전용 가드의 회귀 방지).
     */
    static void requireReadOnly(String sql) {
        String q = sql.strip();
        String up = q.toUpperCase();
        if (!(up.startsWith("SELECT") || up.startsWith("WITH"))) {
            throw new IllegalArgumentException("SELECT/WITH 쿼리만 허용: " + sql);
        }
        for (String bad : List.of("DROP", "DELETE", "UPDATE", "INSERT", "ALTER",
                                  "CREATE", "ATTACH", "COPY", "PRAGMA", "INSTALL", "LOAD")) {
            if (up.contains(bad)) throw new IllegalArgumentException("금지 키워드: " + bad);
        }
    }

    /** SELECT/WITH만 허용, 위험 키워드 차단, 최대 100행. */
    public QueryResult runSelect(String sql) throws SQLException {
        requireReadOnly(sql);
        String q = sql.strip();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(q)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<String> cols = new ArrayList<>();
            for (int i = 1; i <= n; i++) cols.add(md.getColumnName(i));
            List<List<Object>> rows = new ArrayList<>();
            while (rs.next() && rows.size() < 100) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) row.add(rs.getObject(i));
                rows.add(row);
            }
            return new QueryResult(cols, rows);
        }
    }

    private String safeIdent(String s) {
        if (s == null || !s.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("잘못된 테이블명: " + s);
        }
        return s;
    }

    public record QueryResult(List<String> columns, List<List<Object>> rows) {}
}
