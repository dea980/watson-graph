package com.miniwatson.governance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
public interface QueryLogRepository extends JpaRepository<QueryLog, Long> {
    // 끝. 기본 메서드 다 자동 (save, findAll, findById, delete...)
    @Query("SELECT q.model, COUNT(q), AVG(q.latencyMs) FROM QueryLog q GROUP BY q.model")
    List<Object[]> statsByModel();

    @Query("SELECT COALESCE(SUM(q.piiCount), 0) FROM QueryLog q")
    long totalPii();

    @Query("SELECT COALESCE(AVG(q.latencyMs), 0) FROM QueryLog q")
    double avgLatency();

    @Query("SELECT q.feedback, COUNT(q) FROM QueryLog q WHERE q.feedback IS NOT NULL GROUP BY q.feedback")
    List<Object[]> feedbackCounts();

}