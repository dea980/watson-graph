package com.miniwatson.service;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * HWP/HWPX 추출 스모크(integration). 실제 sample 파일에 의존하므로,
 * 파일이 없으면 실패가 아니라 스킵한다(assumeTrue). 라이브러리 연동(hwplib/hwpxlib)이
 * 실제로 텍스트를 뽑는지 한 줄로 확인하는 안전망.
 */
class HwpExtractorTest {

    private final HwpExtractor ex = new HwpExtractor();

    @Test
    void hwpBinaryExtractionIsNotBlank() throws Exception {
        File f = new File("sample/[IBK캐피탈]채용 서류 반환청구서.hwp");
        assumeTrue(f.exists(), "sample .hwp 없음 — 스킵");
        try (var in = new FileInputStream(f)) {
            String text = ex.fromHwp(in);
            assertNotNull(text);
            assertFalse(text.isBlank(), "HWP 추출 텍스트가 비어있음");
        }
    }

    @Test
    void hwpxExtractionIsNotBlank() throws Exception {
        File f = new File("sample/한글 테스트.hwpx");
        assumeTrue(f.exists(), "sample .hwpx 없음 — 스킵");
        try (var in = new FileInputStream(f)) {
            String text = ex.fromHwpx(in);   // 내부에서 임시파일 경유
            assertNotNull(text);
            assertFalse(text.isBlank(), "HWPX 추출 텍스트가 비어있음");
        }
    }
}
