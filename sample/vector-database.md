# Vector Databases

A vector database stores high-dimensional embeddings and supports fast
similarity search. Each item (text, image, audio) is converted into a vector
by an embedding model, and queries are answered by finding the nearest vectors.

## Why not just scan everything?
A brute-force cosine scan is O(n) per query. As the corpus grows this becomes
slow. Approximate Nearest Neighbor (ANN) indexes make search sub-linear.

## Common index types
- **LSH (Locality-Sensitive Hashing)**: random hyperplanes bucket similar
  vectors together, so a query only scores a small candidate set.
- **HNSW**: a navigable small-world graph for logarithmic-time search.
- **IVF**: inverted file index that partitions vectors into clusters.

## Dimensions
Embedding size matters: nomic-embed-text and granite-embedding:30m produce
768 dimensions, while larger models like bge-m3 produce 1024. Higher dimensions
can capture more nuance but cost more storage and compute.
