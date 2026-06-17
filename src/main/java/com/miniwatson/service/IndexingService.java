package com.miniwatson.service;

import com.miniwatson.data.Article;
import com.miniwatson.data.VectorStore;
import org.springframework.stereotype.Service;
import com.miniwatson.data.KeywordIndex;
import com.miniwatson.data.KnowledgeGraph;
import java.util.List;

/** 모든 검색 인덱스(vector, keyword, graph) 갱신을 한 곳에서 담당. ingest와 분리. */
@Service
public class IndexingService {
    private final VectorStore vectorIndex;
    private final KeywordIndex keywordIndex;
    private final KnowledgeGraph knowledgeGraph;

    public IndexingService(VectorStore vectorIndex, KeywordIndex keywordIndex, KnowledgeGraph knowledgeGraph){   // 인터페이스 주입: memory/pgvector 어느 구현이든 받음
        this.vectorIndex = vectorIndex;
        this.keywordIndex = keywordIndex;
        this.knowledgeGraph = knowledgeGraph;
    }
    /** 새 article을 모든 인덱스에 추가. */
    public void index(Article a){
        vectorIndex.add(a);
        keywordIndex.add(a);
        knowledgeGraph.add(a);
    }
    /** 전체 재구성 (삭제 후 동기화 등). */

    public void reindex(List<Article>all){
        vectorIndex.rebuild(all);
        keywordIndex.rebuild(all);
        knowledgeGraph.rebuild(all);
    }

    /**
     * 문서 단위 공출현 엣지 보강. 청크로 쪼개진 문서의 엔티티를 문서 전체 기준으로 연결해
     * 청크 경계에서 끊긴 멀티홉을 복구한다. 그래프 인덱스에만 해당(vector/keyword는 청크 단위 유지).
     */
    public void linkDocument(String namespace, java.util.Set<String> docEntities){
        knowledgeGraph.linkDocument(namespace, docEntities);
    }
}
