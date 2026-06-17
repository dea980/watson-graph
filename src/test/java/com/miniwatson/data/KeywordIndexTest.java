package com.miniwatson.data;

import com.miniwatson.data.ArticleRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KeywordIndexTest {

    private Article doc(long id, String ns, String summary) {
        Article a = new Article();
        a.setId(id);
        a.setNamespace(ns);
        a.setTitle("doc" + id);
        a.setSummary(summary);
        return a;
    }

    private KeywordIndex freshIndex() {
        return new KeywordIndex(new ArticleRepository() {
            public List<Article> loadAll() { return List.of(); }
            public Article save(Article a) { return a; }
            public void saveAll(List<Article> articles) { }
            public boolean deleteById(long id) { return false; }
        });
    }

    @Test
    void exactTokenMatchRanksHigh() {
        KeywordIndex idx = freshIndex();
        idx.add(doc(1, "default", "invoice number INV-2026-0042 total due"));
        idx.add(doc(2, "default", "vector database similarity search"));
        idx.add(doc(3, "default", "retrieval augmented generation"));

        List<Article> top = idx.search("default", "INV-2026-0042", 2);
        assertFalse(top.isEmpty());
        assertEquals(1L, top.get(0).getId(), "invoice doc should rank first");
    }

    @Test
    void namespaceIsolation() {
        KeywordIndex idx = freshIndex();
        idx.add(doc(1, "tenantA", "secret alpha content"));
        idx.add(doc(2, "tenantB", "secret beta content"));

        List<Article> top = idx.search("tenantA", "secret", 5);
        assertEquals(1, top.size());
        assertEquals(1L, top.get(0).getId());
    }

    @Test
    void noMatchReturnsEmpty() {
        KeywordIndex idx = freshIndex();
        idx.add(doc(1, "default", "vector database"));
        assertTrue(idx.search("default", "zzzznonexistent", 5).isEmpty());
    }
}