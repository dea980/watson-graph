package com.miniwatson.governance;

import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PiiRedactionService {
    // 탐지 순서 : 카드 / SSN 을 전화번호 보다 먼저 (겹침 방지)

    private static final Map<String, Pattern> Patterns = new LinkedHashMap<>(){{
        put("[CARD]", Pattern.compile("\\b(?:\\d[ -]?){13,16}\\b"));
        put("[SSN]",   Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"));
        put("[EMAIL]", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
        put("[PHONE]", Pattern.compile("\\b\\d{2,4}[ -]\\d{3,4}[ -]\\d{4}\\b"));
    }};

    public Redaction redact(String text){
        if (text == null || text.isBlank()) return new Redaction(text, 0);
        String result = text;
        int count = 0;
        for (Map.Entry<String, Pattern> e : Patterns.entrySet()){
            Matcher m = e.getValue().matcher(result);
            int c = 0;
            while (m.find()) c++;
            if (c>0){
                result = m.replaceAll(e.getKey());
                count += c;
            }
        }
        return new Redaction(result, count);
    }
    public record Redaction(String text, int count){}
}
