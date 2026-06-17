package com.miniwatson.data;

import java.io.IOException;
import java.util.List;
public interface ArticleRepository {
    List<Article> loadAll() throws IOException;
    Article save(Article article) throws IOException;
    void saveAll(List<Article> articles) throws IOException;

    boolean deleteById(long id) throws IOException;
}
