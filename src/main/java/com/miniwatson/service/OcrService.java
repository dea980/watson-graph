package com.miniwatson.service;


import org.springframework.stereotype.Service;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Service
public class OcrService {
    // 이미지 bytes → 임시파일 → `tesseract <file> stdout` 실행 → 텍스트 반환

    public String extract(byte[] imageBytes) {
        File temp = null;
        try {
            temp = File.createTempFile("ocr-", ".png");
            Files.write(temp.toPath(), imageBytes);
            Process p = new ProcessBuilder("tesseract", temp.getAbsolutePath(), "stdout")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out.trim();
        } catch (IOException | InterruptedException e){
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "";
        } finally {
            if (temp != null) temp.delete();
        }
    }
}
