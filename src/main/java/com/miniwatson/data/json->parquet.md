"Wikipedia/파일 ingest → 청크별 768차원 embedding 생성 → tiered store 저장 → hybrid(vector+BM25) 검색 + rerank → Ollama grounded answer + governance 로그 — 이게 본인 mini watsonx의 full RAG pipeline입니다.
JSON에서 Parquet으로 마이그레이션하니 파일 크기 7배 감소. columnar 포맷 + snappy 압축 효과. watsonx.data가 왜 Parquet을 표준으로 쓰는지 직접 검증했습니다.
그 과정에서 Hadoop 의존성과 Java 21의 SecurityManager 호환성 문제도 디버그해서 해결했습니다. 모던 enterprise stack의 실전 issue를 직접 만져본 셈입니다."

현재 저장 구조 (TieredArticleStore = hot + cold):

data/articles.json       hot   ← 최근/작은 데이터 (embedding 포함, 768차원)
data/articles.parquet    cold  ← columnar + snappy (embedding 포함, 768차원)

                         JSON 대비 Parquet 약 7배 작음 (columnar + snappy)

embedding은 이제 문서당 1개가 아니라 청크당 1개(768차원). query_log / document_catalog는 JPA(H2 dev/demo, PostgreSQL prod).
