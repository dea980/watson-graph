package com.miniwatson.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class ArticleStore {

    private static final String STORAGE_PATH = "./data/articles.json";

    private final ObjectMapper objectMapper;     // ← final

    // ⭐ Constructor가 반드시 있어야 함 + objectMapper 초기화
    public ArticleStore() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.addMixIn(Article.class, EmbeddingPersistMixin.class);
    }
    private abstract static class EmbeddingPersistMixin {
        @JsonProperty(access = JsonProperty.Access.READ_WRITE)
        List<Float> embedding;
    }
    public void saveAll(List<Article> articles) throws IOException {
        File file = new File(STORAGE_PATH);
        file.getParentFile().mkdirs();
        objectMapper.writeValue(file, articles);
    }

    public List<Article> loadAll() throws IOException {
        File file = new File(STORAGE_PATH);
        if (!file.exists()) {
            return new ArrayList<>();
        }
        Article[] array = objectMapper.readValue(file, Article[].class);
        return new ArrayList<>(List.of(array));
    }

    public Article save(Article article) throws IOException {
        List<Article> articles = loadAll();
        if (article.getId() == 0) {   // 직접 호출될 때만 부여
            long nextId = articles.stream().mapToLong(Article::getId).max().orElse(0) + 1;
            article.setId(nextId);
        }
        articles.add(article);
        saveAll(articles);
        return article;
    }
    public boolean deleteByID(long id) throws IOException{
        List<Article> all = loadAll();
        boolean removed = all.removeIf(a -> a.getId() == id);
        if (removed) saveAll(all);
        return removed;
    }
}