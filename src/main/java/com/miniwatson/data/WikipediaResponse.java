package com.miniwatson.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WikipediaResponse {
    private String title;
    private String extract;
    private ContentUrls content_urls;        // ← 여기에 ContentUrls 사용

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentUrls {        // ⭐ inner class 정의 (반드시 static!)
        private Desktop desktop;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Desktop {            // ⭐ inner class 정의 (반드시 static!)
        private String page;
    }
}