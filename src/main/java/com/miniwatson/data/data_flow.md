[ingest("RAG")]   // DataController POST /api/data/ingest (Wikipedia 제목)
↓
[Wikipedia URL 만들기]
url = "https://en.wikipedia.org/api/rest_v1/page/summary/RAG"
↓
[restTemplate.exchange(...)]
HTTP GET → JSON → WikipediaResponse 객체
↓
[Chunker.chunk(content)]   // 기본 recursive, 긴 문서를 청크 N개로 분할
↓
[청크별 embedding]
embeddingService.embed("search_document: " + chunk) → 768차원 벡터
↓
[Article 객체로 변환]
namespace, title(+#번호), summary(=청크), url, embedding, ingestedAt
↓
[ArticleRepository.save(article)]   // @Primary TieredArticleStore (hot JSON + cold Parquet)
↓
[IndexingService.index(saved)]
VectorIndex + KeywordIndex 동시 갱신 (+ DocumentCatalog)
↓
[Article 반환]

참고: /ingest는 Wikipedia summary라 보통 짧아 청크 1개, /ingest-file(multipart)은 추출 텍스트를 여러 청크로 분할.
