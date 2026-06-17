package com.miniwatson.data;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * pgvector 기반 VectorStore — 디스크 영속 + 재시작 후 재인덱싱 불필요.
 *
 * 인메모리 VectorIndex와 같은 VectorStore 계약을 구현하되 저장소만 Postgres+pgvector로 바꾼다.
 * vector.store=pgvector 일 때만 빈으로 뜬다(아니면 VectorIndex가 기본). HybridRetriever는
 * 활성 빈 하나만 주입받으므로 코드 변경이 없다 — 전략 패턴.
 *
 * 설계: TabularSqlService(DuckDB)와 동일하게 자체 JDBC 커넥션을 직접 관리한다. 그래서
 * 감사로그/카탈로그용 H2+JPA 데이터소스를 건드리지 않고 독립적으로 붙는다(멀티 데이터소스 회피).
 *
 * 차원 고정: 컬럼이 vector(768)이라 768 임베더(granite-embedding:278m, nomic)만 적재 가능.
 * 384/1024는 인메모리 전용(EMBEDDINGS.md 9절). 차원 불일치는 적재 시 가드로 막는다.
 */
@Component
@ConditionalOnProperty(name = "vector.store", havingValue = "pgvector")
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);
    private static final int DIM = 768;

    private final ArticleRepository store;   // 콜드 스토어(parquet) = 진실의 원천. 부팅 시 하이드레이션
    private final String url, user, password;
    private Connection conn;

    public PgVectorStore(ArticleRepository store,
                         @Value("${pgvector.url:jdbc:postgresql://localhost:5432/miniwatson}") String url,
                         @Value("${pgvector.user:miniwatson}") String user,
                         @Value("${pgvector.password:miniwatson}") String password) {
        this.store = store;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @PostConstruct
    public synchronized void init() throws Exception {
        conn = DriverManager.getConnection(url, user, password);
        ensureSchema();
        tuneRecall();
        if (count() == 0) {
            // 최초 1회만 parquet에서 적재. 이후 재시작엔 그대로 둬 "재인덱싱 불필요"를 실증.
            rebuild(store.loadAll());
            log.info("[PgVectorStore] hydrated from cold store: {} vectors", count());
        } else {
            log.info("[PgVectorStore] already populated ({} vectors), skip rehydrate", count());
        }
    }

    @PreDestroy
    public void close() throws SQLException {
        if (conn != null) conn.close();
    }

    /** 확장 + 테이블 + HNSW(코사인) 인덱스. 이미 있으면 no-op. */
    private void ensureSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS vector");
            st.execute("""
                CREATE TABLE IF NOT EXISTS article_vectors (
                    id          BIGSERIAL PRIMARY KEY,
                    article_id  BIGINT,
                    namespace   TEXT NOT NULL,
                    title       TEXT,
                    summary     TEXT,
                    url         TEXT,
                    ingested_at TIMESTAMP,
                    embedding   vector(%d)
                )""".formatted(DIM));
            // article_id = 원본 Article.id. HybridRetriever.rrf()가 id로 후보를 식별하므로
            // 이게 비면(null) pg 후보가 전부 한 덩어리로 붕괴해 벡터 순위가 날아간다.
            // namespace 필터가 잦으므로 보조 인덱스
            st.execute("CREATE INDEX IF NOT EXISTS idx_av_ns ON article_vectors (namespace)");
            // ANN: HNSW + 코사인 연산자 클래스. <=> 가 코사인 거리.
            st.execute("CREATE INDEX IF NOT EXISTS idx_av_hnsw ON article_vectors " +
                       "USING hnsw (embedding vector_cosine_ops)");
        }
    }

    /**
     * HNSW + namespace 필터의 recall 손실 보정 (세션 GUC).
     * HNSW는 거리순 후보를 먼저 뽑고 WHERE를 나중에 적용해, 작은 namespace의 정답이
     * 후보(ef_search)에서 탈락한다. ef_search를 코퍼스 규모 이상으로 키워 인메모리 exact와 패리티.
     * iterative_scan(0.8+)은 필터 결과가 부족하면 그래프를 더 훑는다 — 버전 낮으면 무시.
     */
    private void tuneRecall() {
        exec("SET hnsw.ef_search = 1000", "ef_search");                       // 후보 폭 ↑ (코퍼스 442 < 1000 → 사실상 exact)
        exec("SET hnsw.iterative_scan = relaxed_order", "iterative_scan");    // pgvector 0.8+ 전용
    }

    private void exec(String sql, String label) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            log.warn("[PgVectorStore] {} 설정 실패(무시): {}", label, e.getMessage());   // 구버전 pgvector면 GUC 부재
        }
    }

    @Override
    public synchronized void rebuild(List<Article> all) {
        try (Statement st = conn.createStatement()) {
            st.execute("TRUNCATE article_vectors");
        } catch (SQLException e) {
            throw new RuntimeException("pgvector rebuild truncate 실패: " + e.getMessage(), e);
        }
        for (Article a : all) add(a);
    }

    /** 임베딩 있는 article 1건 삽입. 차원 불일치는 건너뛰고 경고(인덱스 오염 방지). */
    @Override
    public synchronized void add(Article a) {
        List<Float> emb = a.getEmbedding();
        if (emb == null || emb.isEmpty()) return;
        if (emb.size() != DIM) {
            log.warn("[PgVectorStore] 차원 불일치 {}!={} — '{}' 건너뜀 (768 임베더만 pgvector 가능)",
                     emb.size(), DIM, a.getTitle());
            return;
        }
        String sql = "INSERT INTO article_vectors(article_id,namespace,title,summary,url,ingested_at,embedding) " +
                     "VALUES (?,?,?,?,?,?,?::vector)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, a.getId());            // 원본 id 보존 (RRF 식별 키)
            ps.setString(2, nsKey(a.getNamespace()));
            ps.setString(3, a.getTitle());
            ps.setString(4, a.getSummary());
            ps.setString(5, a.getUrl());
            ps.setObject(6, a.getIngestedAt());
            ps.setString(7, toVectorLiteral(emb));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("pgvector insert 실패: " + e.getMessage(), e);
        }
    }

    private static final int POOL = 200;   // 앱 정밀 재정렬용 coarse 후보 수 (>= 최대 namespace 크기면 exact)

    /**
     * namespace 안에서 코사인 최근접 topK.
     *
     * 2단계 = coarse retrieve(pg) + exact rerank(app). pg의 vector는 float4라 코사인을 단정밀도로
     * 누적해, double로 누적하는 인메모리 VectorIndex와 top-K 경계에서 순위가 갈린다(TOP_K가 작을수록
     * 민감). 그래서 pg로 후보 POOL개를 넉넉히 받아 와 앱에서 double 코사인으로 다시 정렬한다 —
     * 인메모리와 동일 수식이라 패리티가 회복되고, 대규모에선 표준 "retrieve→rerank" 패턴이 된다.
     */
    @Override
    public synchronized List<Article> search(String namespace, List<Float> query, int topK) {
        String sql = "SELECT article_id,namespace,title,summary,url,ingested_at, embedding::text AS emb " +
                     "FROM article_vectors WHERE namespace = ? " +
                     "ORDER BY embedding <=> ?::vector LIMIT ?";   // pg float4로 coarse 후보
        List<Article> pool = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nsKey(namespace));
            ps.setString(2, toVectorLiteral(query));
            ps.setInt(3, Math.max(topK, POOL));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Article a = new Article();
                    a.setId(rs.getLong("article_id"));   // RRF 식별 키 복원 (0이면 후보 붕괴)
                    a.setNamespace(rs.getString("namespace"));
                    a.setTitle(rs.getString("title"));
                    a.setSummary(rs.getString("summary"));
                    a.setUrl(rs.getString("url"));
                    Timestamp ts = rs.getTimestamp("ingested_at");
                    if (ts != null) a.setIngestedAt(ts.toLocalDateTime());
                    a.setEmbedding(parseVector(rs.getString("emb")));   // 재정렬 + MMR rerank가 청크 임베딩을 씀
                    pool.add(a);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("pgvector search 실패: " + e.getMessage(), e);
        }
        // double 정밀도 코사인으로 재정렬 (VectorIndex와 동일) → 인메모리 패리티
        float[] q = toArray(query);
        double qn = norm(q);
        pool.sort(Comparator.comparingDouble(a -> -cosine(q, qn, toArray(a.getEmbedding()))));
        return pool.size() > topK ? new ArrayList<>(pool.subList(0, topK)) : pool;
    }

    // 인메모리 VectorIndex와 같은 double 누적 코사인 (float4 누적과 달라 top-K 경계가 안정적)
    private static double cosine(float[] q, double qNorm, float[] v) {
        double denom = qNorm * norm(v);
        return denom == 0 ? 0 : dot(q, v) / denom;
    }
    private static double dot(float[] a, float[] b) {
        double s = 0; int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) s += (double) a[i] * b[i];
        return s;
    }
    private static double norm(float[] v) {
        double s = 0; for (float x : v) s += (double) x * x;
        return Math.sqrt(s);
    }
    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    @Override
    public synchronized Map<String, Object> stats() {
        Map<String, Object> outMap = new LinkedHashMap<>();
        outMap.put("mode", "pgvector");
        outMap.put("dimension", DIM);
        Map<String, Object> perNs = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT namespace, COUNT(*) c FROM article_vectors GROUP BY namespace ORDER BY namespace")) {
            while (rs.next()) {
                Map<String, Object> ns = new LinkedHashMap<>();
                ns.put("vectors", rs.getLong("c"));
                perNs.put(rs.getString("namespace"), ns);
            }
        } catch (SQLException e) {
            outMap.put("error", e.getMessage());
        }
        outMap.put("namespaces", perNs);
        return outMap;
    }

    // ---- helpers ----

    private long count() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM article_vectors")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** List<Float> -> "[0.1,0.2,...]" (pgvector 리터럴). */
    static String toVectorLiteral(List<Float> v) {
        StringBuilder sb = new StringBuilder(v.size() * 8).append('[');
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(v.get(i));
        }
        return sb.append(']').toString();
    }

    /** "[0.1,0.2,...]" -> List<Float>. */
    static List<Float> parseVector(String s) {
        if (s == null || s.length() < 2) return List.of();
        String body = s.substring(1, s.length() - 1);   // strip [ ]
        if (body.isBlank()) return List.of();
        String[] parts = body.split(",");
        List<Float> out = new ArrayList<>(parts.length);
        for (String p : parts) out.add(Float.parseFloat(p.trim()));
        return out;
    }

    private static String nsKey(String ns) {
        return (ns == null || ns.isBlank()) ? "default" : ns;
    }
}
