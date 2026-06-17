package com.miniwatson.service;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component("fixed")
public class FixedChunker implements Chunker {

    @Override
    public List<String> chunk(String text, int maxSize) {
        int overlap = 150;
        int step = Math.max(1, maxSize - overlap);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += step) {
            String c = text.substring(i, Math.min(text.length(), i + maxSize)).trim();
            if (!c.isBlank()) out.add(c);
            if (i + maxSize >= text.length()) break;
        }
        return out;
    }
}