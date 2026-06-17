#!/usr/bin/env bash
# golden.json 코퍼스를 고정 재현 (모델 비교 때 매번 동일 입력 보장)
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
NS_DEFAULT=default
NS_IBM="IBM-blueprint-for-agentic-opeation"          # golden의 철자 그대로(opeation, 오타 포함)
IBM_PDF=${IBM_PDF:-sample/the-blueprint-for-agentic-operations-report.pdf}

echo "== default: Wikipedia 3건 =="
for T in "Retrieval-augmented generation" "Vector database" "Wiki"; do
  curl -s -X POST "$BASE/api/data/ingest" \
    --data-urlencode "title=$T" --data-urlencode "namespace=$NS_DEFAULT" >/dev/null
  echo "  + $T"
done

echo "== default: invoice (OCR 경로) =="
curl -s -X POST "$BASE/api/multimodal/ingest" \
  -F "image=@sample/invoice.png" -F "namespace=$NS_DEFAULT" >/dev/null
echo "  + invoice.png"

echo "== IBM: blueprint PDF =="
if [ -f "$IBM_PDF" ]; then
  curl -s -X POST "$BASE/api/data/ingest-file" \
    -F "file=@$IBM_PDF" -F "namespace=$NS_IBM" >/dev/null
  echo "  + $IBM_PDF"
else
  echo "  SKIP: $IBM_PDF 없음 → IBM namespace 10케이스 빠짐"
fi

echo "== IBM CEO study (별도 namespace) =="
CEO_PDF=${CEO_PDF:-sample/2026-ceo-study-rewiring-the-c-suite-report.pdf}
NS_CEO="IBM-ceo-study-2026"
if [ -f "$CEO_PDF" ]; then
  curl -s -X POST "$BASE/api/data/ingest-file" \
    -F "file=@$CEO_PDF" -F "namespace=$NS_CEO" >/dev/null
  echo "  + $CEO_PDF"
else
  echo "  SKIP: $CEO_PDF 없음 → CEO study 케이스 빠짐"
fi

echo "== 다양한 포맷 (Tika: html/docx/pptx/txt/md, HWP/HWPX) — 표(csv/xlsx)는 SQL 트랙 =="
declare -A FILES=(
  ["kr-bcg"]="sample/BCG AI 보고서 분석_ 59개국 데이터와 현장의 차이 _ AX 연구소.html"
  ["kr-hackathon"]="sample/hackathon2026.docx"
  ["kr-medical"]="sample/260205_의료인공지능연구회_발표자료_김대엽.pptx"
  ["dli-rag"]="sample/DLI-RAG-Slides.pptx"
  ["kr-hwp"]="sample/[IBK캐피탈]채용 서류 반환청구서.hwp"
  ["kr-hwpx"]="sample/한글 테스트.hwpx"
  ["notes-rag"]="sample/rag-notes.txt"
  ["notes-vdb"]="sample/vector-database.md"
)
# 표(CSV)는 RAG가 아니라 text-to-SQL 경로로 처리 → eval/run_eval.py --sql, /api/tabular (TABULAR-SQL.md).
# nasa.csv(4687행)도 SQL은 load가 즉시(zero-ETL)라 RAG 임베딩 부담이 없다.
for ns in "${!FILES[@]}"; do
  f="${FILES[$ns]}"
  if [ -f "$f" ]; then
    curl -fsS -X POST "$BASE/api/data/ingest-file" -F "file=@$f" -F "namespace=$ns" >/dev/null \
      && echo "  + [$ns] $(basename "$f")" || echo "  FAIL [$ns] $(basename "$f")  (HTTP 에러 — 위 메시지 참고)"
  else echo "  MISSING $f"; fi
done

echo "== 이미지 (멀티모달 OCR+Vision) =="
curl -s -X POST "$BASE/api/multimodal/ingest" -F "image=@sample/revenue-chart.png" -F "namespace=revenue" >/dev/null \
  && echo "  + [revenue] revenue-chart.png" || echo "  FAIL revenue-chart"
curl -s -X POST "$BASE/api/multimodal/ingest" -F "image=@sample/삼성희망디딤돌 포스터 .jpg" -F "namespace=kr-poster" >/dev/null \
  && echo "  + [kr-poster] 삼성희망디딤돌 포스터" || echo "  FAIL poster"

echo "== index stats =="
curl -s "$BASE/api/data/index/stats" | python3 -m json.tool