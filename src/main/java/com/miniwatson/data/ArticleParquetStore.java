package com.miniwatson.data;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class ArticleParquetStore {

    private static final String STORAGE_PATH = "./data/articles.parquet";
    private final Schema schema;

    public ArticleParquetStore() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("article.avsc")) {
            if (is == null) {
                throw new IOException("article.avsc not found in classpath");
            }
            this.schema = new Schema.Parser().parse(is);
        }
    }

    public void saveAll(List<Article> articles) throws IOException {
        Path path = new Path(STORAGE_PATH);

        File parentDir = new File(STORAGE_PATH).getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            if (!created) {
                System.err.println("Warning: Failed to create parent dir: " + parentDir);
            }
        }

        // Parquet은 overwrite 불가 → 기존 파일 삭제
        File existing = new File(STORAGE_PATH);
        if (existing.exists() && !existing.delete()) {
            throw new IOException("Failed to delete existing Parquet file: " + STORAGE_PATH);
        }

        Configuration conf = new Configuration();

        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(path)
                .withSchema(schema)
                .withConf(conf)
                .withDataModel(GenericData.get())            // Writer는 OK
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .build()) {

            for (Article article : articles) {
                GenericRecord record = new GenericData.Record(schema);
                record.put("id", article.getId());
                String ns = (article.getNamespace() == null || article.getNamespace().isBlank())
                        ? "default" : article.getNamespace();
                record.put("namespace", ns);
                record.put("title", article.getTitle());
                record.put("summary", article.getSummary());
                record.put("url", article.getUrl());
                record.put("ingestedAt", article.getIngestedAt().toString());
                record.put("embedding", article.getEmbedding());
                writer.write(record);
            }
        }

        System.out.println("[Parquet] Saved " + articles.size() + " articles → " + STORAGE_PATH);
    }

    public List<Article> loadAll() throws IOException {
        File file = new File(STORAGE_PATH);
        if (!file.exists() || file.length() < 8) {   // 빈/손상 파일은 무시
            return new ArrayList<>();
        }

        Path path = new Path(STORAGE_PATH);
        Configuration conf = new Configuration();
        List<Article> articles = new ArrayList<>();

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(path)
                .withConf(conf)                              // Reader는 withDataModel 없음
                .build()) {

            GenericRecord record;
            int idx = 0;
            while ((record = reader.read()) != null) {
                Article article = new Article();
                article.setId((Long) record.get("id"));
                // backward compat: 예전 Parquet 파일에는 namespace 필드가 없다.
                boolean hasNs = record.getSchema().getField("namespace") != null;
                Object nsObj = hasNs ? record.get("namespace") : null;
                article.setNamespace(nsObj != null ? nsObj.toString() : "default");
                article.setTitle(record.get("title").toString());
                article.setSummary(record.get("summary").toString());
                article.setUrl(record.get("url").toString());
                article.setIngestedAt(LocalDateTime.parse(record.get("ingestedAt").toString()));

                Object embObj = record.get("embedding");
                List<Float> embedding = new ArrayList<>();

                if (embObj instanceof List<?> rawList) {
                    for (Object item : rawList) {
                        if (item instanceof Number num) {
                            embedding.add(num.floatValue());
                        }
                    }
                }

                article.setEmbedding(embedding);
                articles.add(article);

                System.out.println("[Parquet] Loaded #" + idx
                        + " id=" + article.getId()
                        + " title=" + article.getTitle()
                        + " embedding.size=" + embedding.size());
                idx++;
            }
        }

        System.out.println("[Parquet] loadAll → " + articles.size() + " articles");
        return articles;
    }

    public Article save(Article article) throws IOException {
        List<Article> articles = loadAll();
        long nextId = articles.stream().mapToLong(Article::getId).max().orElse(0) + 1;
        article.setId(nextId);
        articles.add(article);
        saveAll(articles);
        return article;
    }
    public boolean deleteById(long id) throws IOException {
        List<Article> all = loadAll();
        boolean removed = all.removeIf(a -> a.getId() == id);
        if (removed) saveAll(all);   // Parquet 전체 재작성 (기존 패턴 그대로)
        return removed;
    }
}