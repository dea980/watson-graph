package com.miniwatson.service;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component("recursive")
public class RecursiveChunker implements Chunker {

    private static final String[] SEPARATORS = {"\n\n", "\n", ". ", " "};

    @Override
    public List<String> chunk(String text, int maxSize) {
        return split(text, 0, maxSize);
    }

    private List<String> split(String text, int depth, int maxSize) {
        List<String> out = new ArrayList<>();
        if (text.length() <= maxSize) {
            if (!text.isBlank()) out.add(text.trim());
            return out;
        }
        if (depth >= SEPARATORS.length) {
            for (int i = 0; i < text.length(); i += maxSize)
                out.add(text.substring(i, Math.min(text.length(), i + maxSize)).trim());
            return out;
        }
        StringBuilder buf = new StringBuilder();
        for (String part : text.split(Pattern.quote(SEPARATORS[depth]))) {
            if (buf.length() + part.length() > maxSize && buf.length() > 0) {
                out.addAll(split(buf.toString(), depth + 1, maxSize));
                buf.setLength(0);
            }
            buf.append(part).append(SEPARATORS[depth]);
        }
        if (buf.length() > 0) out.addAll(split(buf.toString(), depth + 1, maxSize));
        return out;
    }
}