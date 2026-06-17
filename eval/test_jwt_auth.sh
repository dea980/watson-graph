#!/usr/bin/env bash
# C(JWT) 모드 인증 테스트. 앱이 SECURITY_ENABLED=true SECURITY_MODE=jwt 로 떠 있어야 함.
# 사용: bash eval/test_jwt_auth.sh
set -u
SECRET='demo-hs256-secret-key-min-32-bytes-long!!'   # application-dev.yaml 의 security.jwt-secret 과 동일해야 함
API='localhost:8080/api/rag/ask'

TOKEN=$(python3 -c "import jwt,time; print(jwt.encode({'sub':'acme','namespaces':'default,kr-bcg','exp':int(time.time())+3600},'$SECRET','HS256'))")
if [ -z "$TOKEN" ]; then echo "토큰 민팅 실패 — pip install pyjwt --break-system-packages 했나?"; exit 1; fi
echo "token: ${TOKEN:0:24}...  (len ${#TOKEN})"

code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }

echo -n "1) no token        (expect 401): "
code -X POST "$API" -H 'Content-Type: application/json' \
  -d '{"question":"x","namespace":"default"}'; echo

echo -n "2) token + default (expect 200): "
code -X POST "$API" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"RAG?","namespace":"default"}'; echo

echo -n "3) token + IBM-ceo (expect 403): "
code -X POST "$API" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"x","namespace":"IBM-ceo-study-2026"}'; echo
