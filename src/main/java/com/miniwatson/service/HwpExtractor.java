package com.miniwatson.service;

import org.springframework.stereotype.Component;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Component
public class HwpExtractor {

    // .hwp (5.x 바이너리) — hwplib은 InputStream 직접 OK
    public String fromHwp(InputStream in) throws Exception {
        var hwp = kr.dogfoot.hwplib.reader.HWPReader.fromInputStream(in);
        return kr.dogfoot.hwplib.tool.textextractor.TextExtractor.extract(
                hwp, kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod
                        .InsertControlTextBetweenParagraphText);
    }

    // .hwpx — hwpxlib reader엔 fromInputStream 없음 → 임시파일 경유.
    // 이미지 포함 문서에서 hwpxlib 1.0.5가 NPE날 수 있어, 실패 시 PrvText(평문 미리보기)로 폴백.
    public String fromHwpx(InputStream in) throws Exception {
        File tmp = File.createTempFile("upload-", ".hwpx");
        try {
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try {
                var hwpx = kr.dogfoot.hwpxlib.reader.HWPXReader.fromFile(tmp);
                String text = kr.dogfoot.hwpxlib.tool.textextractor.TextExtractor.extract(
                        hwpx,
                        kr.dogfoot.hwpxlib.tool.textextractor.TextExtractMethod.InsertControlTextBetweenParagraphText,
                        true,
                        null);
                if (text != null && !text.isBlank()) return text;
            } catch (Exception libErr) {
                // hwpxlib 1.0.5: 이미지 등 일부 객체에서 manifest null → NPE. PrvText로 폴백.
            }
            return previewText(tmp);
        } finally {
            tmp.delete();   // 임시파일 정리
        }
    }

    /** HWPX(zip) 안의 평문 미리보기(Preview/PrvText.txt, UTF-8). lib 추출 실패 시 폴백. */
    private String previewText(File hwpx) throws Exception {
        try (var zf = new java.util.zip.ZipFile(hwpx)) {
            var entry = zf.getEntry("Preview/PrvText.txt");
            if (entry == null) return "";
            try (var is = zf.getInputStream(entry)) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }
}