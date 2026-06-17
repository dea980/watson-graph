package com.miniwatson.service;

import org.junit.jupiter.api.Test;

import static com.miniwatson.service.IngestionService.SourceFormat.*;
import static com.miniwatson.service.IngestionService.formatOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * formatOf: 파일 확장자 -> 추출기 라우팅. 이 분기가 틀어지면 HWP가 Tika로 잘못 가서
 * "추출 실패"로 ingest가 깨진다(실제로 그 버그를 한 번 겪음).
 */
class IngestionServiceTest {

    @Test
    void hwpExtensionRoutesToHwp() {
        assertEquals(HWP, formatOf("채용서류.hwp"));
    }

    @Test
    void hwpxExtensionRoutesToHwpx() {
        assertEquals(HWPX, formatOf("공고.hwpx"));   // .hwp 접미사를 포함하지만 HWPX로 먼저 잡혀야 함
    }

    @Test
    void officeAndWebFormatsRouteToTika() {
        for (String f : new String[]{"a.pdf", "b.docx", "c.pptx", "d.xlsx", "e.html", "f.txt", "g.md", "h.csv"}) {
            assertEquals(TIKA, formatOf(f), f + " 는 TIKA여야 함");
        }
    }

    @Test
    void isCaseInsensitive() {
        assertEquals(HWP, formatOf("FORM.HWP"));
        assertEquals(HWPX, formatOf("Notice.HwpX"));
    }

    @Test
    void missingExtensionNullAndEmptyFallBackToTika() {
        assertEquals(TIKA, formatOf("README"));
        assertEquals(TIKA, formatOf(null));
        assertEquals(TIKA, formatOf(""));
    }

    @Test
    void judgesByFinalExtensionEvenIfHwpInMiddle() {
        assertEquals(TIKA, formatOf("report.hwp.pdf"));   // .pdf로 끝나니 TIKA
        assertEquals(HWP, formatOf("backup.pdf.hwp"));     // .hwp로 끝나니 HWP
    }
}
