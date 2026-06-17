package com.miniwatson.governance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.List;


public interface DocumentCatalogRepository extends JpaRepository<DocumentCatalog, Long> {
    Optional<DocumentCatalog> findByTitleAndNamespace(String title, String namespace);

    @Query("SELECT d.sourceType, COUNT(d), COALESCE(SUM(d.chunks),0) FROM DocumentCatalog d GROUP BY d.sourceType")
    List<Object[]> statsBySourceType();
}
