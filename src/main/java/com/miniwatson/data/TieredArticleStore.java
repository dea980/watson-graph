package com.miniwatson.data;


import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
@Component
@Primary
public class TieredArticleStore implements ArticleRepository{
    private final ArticleStore hot;
    private final ArticleParquetStore cold;

    @Value("${storage.tier.threshold:100}")
    private int threshold;

    public TieredArticleStore(ArticleStore hot, ArticleParquetStore cold){
        this.hot = hot;
        this.cold = cold;
    }

    public List<Article> loadAll() throws IOException{
        List<Article> all = new ArrayList<>(cold.loadAll());
        all.addAll(hot.loadAll());
        return all;
    }

    public Article save(Article a) throws IOException {
        List<Article> all = loadAll();
        long nextId = all.stream().mapToLong(Article::getId).max().orElse(0) + 1;
        a.setId(nextId);
        hot.save(a);   // JSON에 append
        if (hot.loadAll().size() >= threshold) compact();
        return a;
    }
    public boolean deleteById(long id) throws IOException{
        boolean isHot = hot.deleteByID(id);
        boolean isCold = cold.deleteById(id);
        return isHot || isCold;
    }
    private void compact() throws IOException{
        List<Article> merged = loadAll(); // cold + hot
        cold.saveAll(merged); // Parquet 으로 압축
        hot.saveAll(new ArrayList<>()); // hot 비우기
    }

    public void saveAll(List<Article> articles) throws IOException {
        cold.saveAll(articles);
    }

}
