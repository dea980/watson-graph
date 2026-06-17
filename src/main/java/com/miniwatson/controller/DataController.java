package com.miniwatson.controller;

import com.miniwatson.data.Article;
//import com.miniwatson.data.ArticleParquetStore;
import com.miniwatson.data.VectorStore;
import com.miniwatson.data.KnowledgeGraph;

import com.miniwatson.service.IngestionService;
import com.miniwatson.service.IndexingService;
import com.miniwatson.governance.DocumentCatalogRepository;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.miniwatson.data.ArticleRepository;
import com.miniwatson.service.OllamaService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/data")
public class DataController {

    private static final String DEFAULT_NS = "default";

    private final IngestionService ingestionService;
    //private final ArticleParquetStore articleStore;
    private final ArticleRepository articleStore;
    private final VectorStore vectorIndex;
    private final OllamaService ollamaService;
    private final IndexingService indexingService;
    private final DocumentCatalogRepository catalogRepo;
    private final KnowledgeGraph knowledgeGraph;

    public DataController(IngestionService ingestionService,
                          ArticleRepository articleStore,
                          VectorStore vectorIndex,
                          OllamaService ollamaService,
                          DocumentCatalogRepository catalogRepo,
                          IndexingService indexingService,
                          KnowledgeGraph knowledgeGraph
                          ) {
        this.ingestionService = ingestionService;
        this.articleStore = articleStore;
        this.vectorIndex = vectorIndex;
        this.ollamaService = ollamaService;
        this.catalogRepo = catalogRepo;
        this.indexingService = indexingService;
        this.knowledgeGraph = knowledgeGraph;
    }

    /**
     * Single ingest.
     * POST /api/data/ingest?title=RAG&namespace=acme
     */
    @PostMapping("/ingest")
    public Article ingest(@RequestParam String title,
                          @RequestParam(required = false, defaultValue = DEFAULT_NS) String namespace)
            throws IOException {
        return ingestionService.ingest(title, namespace);
    }

    /**
     * Batch ingest.
     * POST /api/data/ingest-batch?namespace=acme
     * { "topics": ["RAG", "Vector database", "Embedding"] }
     */
    @PostMapping("/ingest-batch")
    public Map<String, Object> ingestBatch(
            @RequestBody Map<String, List<String>> body,
            @RequestParam(required = false, defaultValue = DEFAULT_NS) String namespace) throws IOException {
        List<String> topics = body.get("topics");

        if (topics == null || topics.isEmpty()) {
            return Map.of(
                    "success", false,
                    "error", "Request body must contain non-empty 'topics' array"
            );
        }

        List<Article> ingested = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();

        for (String title : topics) {
            try {
                ingested.add(ingestionService.ingest(title, namespace));
            } catch (Exception e) {
                failed.add(Map.of(
                        "title", title,
                        "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
                ));
            }
        }

        return Map.of(
                "success", true,
                "namespace", namespace,
                "ingested", ingested.size(),
                "failed", failed.size(),
                "articles", ingested,
                "errors", failed
        );
    }

    /**
     * summarize pdf file

     */
    @PostMapping("/summarize/{id}")
    public Map<String, Object> summarize(@PathVariable long id) throws IOException {
        List<Article> all = articleStore.loadAll();
        Article target = all.stream().filter(x -> x.getId() == id).findFirst()
                .orElseThrow(() -> new RuntimeException("Article not found: " + id));

        // "file.pdf #3" → "file.pdf" 로 base 추출
        String base = target.getTitle().replaceAll(" #\\d+$", "");

        // 같은 문서의 모든 청크를 순서대로 합침
        String doc = all.stream()
                .filter(a -> a.getTitle().replaceAll(" #\\d+$", "").equals(base))
                .map(Article::getSummary)
                .reduce("", (x, y) -> x + "\n" + y);

        if (doc.length() > 8000) doc = doc.substring(0, 8000);
        String prompt = "Summarize the following document concisely:\n\n" + doc;
        String summary = ollamaService.ask(prompt, null, "summarize: " + base);
        return Map.of("id", id, "title", base, "summary", summary);
    }
    /**
     * Article 목록. namespace 미지정 시 전체 반환.
     * GET /api/data/articles            → all
     * GET /api/data/articles?namespace=acme → tenant only
     */
    @GetMapping("/articles")
    public List<Article> getAllArticles(@RequestParam(required = false) String namespace) throws IOException {
        List<Article> all = articleStore.loadAll();
        if (namespace == null || namespace.isBlank()) {
            return all;
        }
        List<Article> filtered = new ArrayList<>();
        for (Article a : all) {
            String ns = (a.getNamespace() == null || a.getNamespace().isBlank()) ? DEFAULT_NS : a.getNamespace();
            if (ns.equals(namespace)) {
                filtered.add(a);
            }
        }
        return filtered;
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> getDocuments(@RequestParam(required = false) String namespace) throws IOException{
        List<Article> all = getAllArticles(namespace);

        // baseTitle 로 그룹핑 (" #3" 제거)
        Map<String, List<Article>> grouped = new LinkedHashMap<>();
        for (Article a : all) {
            String ns = (a.getNamespace() == null || a.getNamespace().isBlank()) ? DEFAULT_NS : a.getNamespace();
            String base = a.getTitle().replaceAll(" #\\d+$", "");
            String key = ns + "||" + base;                    // ns + title 복합 키
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
        }

        List<Map<String, Object>> docs = new ArrayList<>();
        for (List<Article> chunks : grouped.values()) {
            Article first = chunks.get(0);
            String ns = (first.getNamespace() == null || first.getNamespace().isBlank()) ? DEFAULT_NS : first.getNamespace();
            docs.add(Map.of(
                    "title", first.getTitle().replaceAll(" #\\d+$", ""),
                    "chunks", chunks.size(),
                    "namespace", ns,
                    "url", first.getUrl() == null ? "" : first.getUrl(),
                    "ids", chunks.stream().map(Article::getId).toList()
            ));
        }
        return docs;
    }
    @DeleteMapping("/documents")
    public Map<String, Object> deleteDocument(@RequestParam String title,
                                              @RequestParam(required = false, defaultValue = DEFAULT_NS) String namespace) throws IOException {
        List<Article> all = articleStore.loadAll();
        List<Long> toDelete = all.stream()
                .filter(a -> {
                    String ns = (a.getNamespace() == null || a.getNamespace().isBlank()) ? DEFAULT_NS : a.getNamespace();
                    String base = a.getTitle().replaceAll(" #\\d+$", "");
                    return ns.equals(namespace) && base.equals(title);
                })
                .map(Article::getId)
                .toList();

        int removed = 0;
        for (Long id : toDelete) {
            if (articleStore.deleteById(id)) removed++;
        }
        if (removed > 0) indexingService.reindex(articleStore.loadAll());   // vectorIndex.rebuild → reindex   // 인덱스 동기화 1회
        catalogRepo.findByTitleAndNamespace(title, namespace)
                .ifPresent(catalogRepo::delete);
        return Map.of("title", title, "namespace", namespace, "deletedChunks", removed);
    }

    /**
     * Article 개수 (dash board 용). namespace 옵션.
     * GET /api/data/count[?namespace=acme]
     */
    @GetMapping("/count")
    public Map<String, Integer> getCount(@RequestParam(required = false) String namespace) throws IOException {
        return Map.of("count", getAllArticles(namespace).size());
    }

    /**
     * 벡터 인덱스 상태 (mode, hyperplanes, per-namespace vectors/buckets).
     * GET /api/data/index/stats
     */
    @GetMapping("/index/stats")
    public Map<String, Object> indexStats() {
        return vectorIndex.stats();
    }

    /**
     * 지식그래프 상태. namespace를 주면 그 테넌트의 노드/엣지 수 + 엣지 타입별 분포를 반환한다.
     * ingest가 그래프까지 됐는지, 타입드 엣지(시드 관계)가 생성됐는지 검증용.
     * GET /api/data/graph/stats?namespace=kr-securities
     */
    @GetMapping("/graph/stats")
    public Map<String, Object> graphStats(
            @RequestParam(value = "namespace", required = false) String namespace) {
        return (namespace == null || namespace.isBlank())
                ? knowledgeGraph.stats()
                : knowledgeGraph.stats(namespace);
    }

    @PostMapping("/ingest-file")
    public List<Article> ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "namespace", required = false, defaultValue = "default") String namespace)
            throws IOException {
        return ingestionService.ingestText(file, namespace);
    }
    @DeleteMapping("/articles/{id}")
    public Map<String, Object> deleteArticle(@PathVariable long id) throws IOException {
        boolean removed = articleStore.deleteById(id);
        if (removed) {
            indexingService.reindex(articleStore.loadAll());   // vectorIndex.rebuild → reindex
        }
        return Map.of("deleted", removed, "id", id);
    }

}
