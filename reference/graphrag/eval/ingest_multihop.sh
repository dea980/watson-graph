#!/usr/bin/env bash
# 증권 멀티홉 픽스처 코퍼스를 kr-securities 네임스페이스로 적재.
# 기존 eval/ingest_corpus.sh 와 동일한 /api/data/ingest-file 경로 사용 (Tika가 .md 처리).
# 앱이 떠 있어야 한다 (BASE 기본 http://localhost:8080).
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
NS=${NS:-kr-securities}
HERE="$(cd "$(dirname "$0")" && pwd)"
CORPUS="$HERE/corpus"

echo "== $NS 코퍼스 적재 (from $CORPUS) =="
for f in "$CORPUS"/*.md; do
  curl -fsS -X POST "$BASE/api/data/ingest-file" \
    -F "file=@$f" -F "namespace=$NS" >/dev/null \
    && echo "  + $(basename "$f")" \
    || echo "  FAIL $(basename "$f")"
done

echo "== index stats =="
curl -s "$BASE/api/data/index/stats" | python3 -m json.tool || true
