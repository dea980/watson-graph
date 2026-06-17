package com.miniwatson.controller;

import com.miniwatson.service.TabularSqlService;
import com.miniwatson.service.TextToSqlService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 표 파일을 DuckDB로 SQL 질의 (watsonx.data 라이크하우스 경로).
 * 비정형 -> RAG(/api/rag), 정형(표) -> SQL(/api/tabular).
 */
@RestController
@RequestMapping("/api/tabular")
public class TabularController {

    private final TabularSqlService sqlService;
    private final TextToSqlService textToSql;

    public TabularController(TabularSqlService sqlService, TextToSqlService textToSql) {
        this.sqlService = sqlService;
        this.textToSql = textToSql;
    }

    /**
     * POST /api/tabular/load?table=revenue&path=sample/x.csv
     * xlsx는 헤더가 N행 아래일 수 있어 &headerRow=6 처럼 지정(기본 0).
     */
    @PostMapping("/load")
    public Map<String, Object> load(@RequestParam String table, @RequestParam String path,
                                    @RequestParam(required = false, defaultValue = "0") int headerRow) throws Exception {
        if (path.toLowerCase().endsWith(".xlsx")) {
            sqlService.registerXlsx(table, path, headerRow);
        } else {
            sqlService.registerCsv(table, path);
        }
        return Map.of("table", table, "schema", sqlService.schema(table));
    }

    /** POST /api/tabular/ask  body: {"table":"revenue","question":"..."} */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> body) throws Exception {
        return textToSql.ask(body.get("table"), body.get("question"));
    }
}
