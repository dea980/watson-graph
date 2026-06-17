#!/usr/bin/env python3
"""
MiniWatson eval harness.

Two axes:
  1. Retrieval recall  — did the right chunk get retrieved? (objective)
  2. Answer quality     — is the generated answer correct? (LLM-as-judge)

Retrieval sweeps rerank x hybrid via the EVAL-ONLY request overrides, in one run,
no restart. Answer quality uses an LLM (Ollama) to grade actual vs expected.

두 갈래의 데이터:
  - 비정형 텍스트 -> RAG retrieval/judge (golden.json)
  - 정형 표(CSV)   -> text-to-SQL (golden_sql.json, /api/tabular)

Usage:
    python3 eval/run_eval.py             # retrieval recall sweep (default, RAG)
    python3 eval/run_eval.py --judge     # + LLM-as-judge answer quality
    python3 eval/run_eval.py --sql       # text-to-SQL track (tabular, golden_sql.json)
    API=http://localhost:8080 OLLAMA=http://localhost:11434 python3 eval/run_eval.py --judge
"""
import json, os, sys, urllib.request, urllib.parse

API = os.environ.get("API", "http://localhost:8080")
OLLAMA = os.environ.get("OLLAMA", "http://localhost:11434")
JUDGE_MODEL = os.environ.get("JUDGE_MODEL", "ibm/granite4:latest")
HERE = os.path.dirname(os.path.abspath(__file__))

CONFIGS = [
    {"rerank": "none", "hybrid": False},
    {"rerank": "none", "hybrid": True},
    {"rerank": "llm",  "hybrid": True},
    {"rerank": "mmr",  "hybrid": True},
]


def ask(question, namespace, rerank=None, hybrid=None):
    payload = {"question": question, "namespace": namespace}
    if rerank is not None:
        payload["rerank"] = rerank      # EVAL-ONLY override
    if hybrid is not None:
        payload["hybrid"] = hybrid      # EVAL-ONLY override
    req = urllib.request.Request(
        f"{API}/api/rag/ask",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r)


def hit(case, resp):
    """Retrieval hit: a source matches the expectation."""
    sources = resp.get("sources", []) or []
    blob = " ".join(
        (s.get("title", "") + " " + s.get("summary", "")).lower() for s in sources
    )
    if "expectTitleContains" in case:
        if case["expectTitleContains"].lower() not in blob:
            return False
    if "expectKeywords" in case:
        for kw in case["expectKeywords"]:
            if kw.lower() not in blob:
                return False
    return True


def judge(question, expected, actual):
    """LLM-as-judge: grade ACTUAL answer vs EXPECTED meaning, 1-5. Returns int or None."""
    prompt = (
        "You are grading a RAG answer. Score 1-5 how well the ACTUAL answer matches "
        "the EXPECTED meaning and correctly answers the question. "
        "5=fully correct, 1=wrong/irrelevant. Reply with ONLY a single digit 1-5.\n\n"
        f"Question: {question}\nExpected: {expected}\nActual: {actual}\nScore:"
    )
    body = {
        "model": JUDGE_MODEL,
        "prompt": prompt,
        "stream": False,
        "think": False,
        "options": {"num_predict": 4},
    }
    req = urllib.request.Request(
        f"{OLLAMA}/api/generate",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            text = json.load(r).get("response", "")
        for ch in text:
            if ch in "12345":
                return int(ch)
        return None
    except Exception:
        return None


def recall_sweep(cases):
    n = len(cases)
    print(f"\n=== Retrieval recall ({n} cases) ===\n")
    print(f"{'config':32} {'recall':>10}   misses")
    print("-" * 70)
    for cfg in CONFIGS:
        hits, misses = 0, []
        for c in cases:
            try:
                resp = ask(c["question"], c.get("namespace", "default"),
                           cfg["rerank"], cfg["hybrid"])
                ok = hit(c, resp)
            except Exception:
                ok = False
            if ok:
                hits += 1
            else:
                misses.append(c["id"])
        label = f"rerank={cfg['rerank']}, hybrid={cfg['hybrid']}"
        pct = f"{hits}/{n} ({hits/n:.0%})"
        print(f"{label:32} {pct:>10}   {', '.join(misses) if misses else '-'}")
    print()


def judge_run(cases):
    """Answer quality with default config (whatever the server is set to)."""
    graded = [c for c in cases if "expectAnswer" in c]
    if not graded:
        print("\n(no cases have 'expectAnswer' — add it to golden.json to use --judge)\n")
        return
    print(f"\n=== Answer quality (LLM-as-judge: {JUDGE_MODEL}, {len(graded)} cases) ===\n")
    print(f"{'id':24} {'score':>6}")
    print("-" * 40)
    total, counted = 0, 0
    for c in graded:
        try:
            resp = ask(c["question"], c.get("namespace", "default"))
            score = judge(c["question"], c["expectAnswer"], resp.get("answer", ""))
        except Exception:
            score = None
        if score is not None:
            total += score
            counted += 1
        print(f"{c['id']:24} {('-' if score is None else str(score)):>6}")
    if counted:
        print(f"\navg answer score: {total/counted:.2f} / 5  ({counted} graded)\n")


def sql_load(table, path, header_row=0):
    """표 파일을 DuckDB 테이블로 등록 (POST /api/tabular/load). xlsx는 header_row로 제목행 skip."""
    url = (f"{API}/api/tabular/load?table={table}"
           f"&path={urllib.parse.quote(path)}&headerRow={header_row}")
    req = urllib.request.Request(url, method="POST")
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)


def sql_ask(table, question):
    """질문 -> LLM SQL -> 실행 (POST /api/tabular/ask) -> {sql, columns, rows}."""
    req = urllib.request.Request(
        f"{API}/api/tabular/ask",
        data=json.dumps({"table": table, "question": question}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r)


def sql_run():
    """text-to-SQL 트랙: 각 케이스의 표를 load하고 질문해 expect 값이 결과 행에 있는지 확인."""
    path = os.path.join(HERE, "golden_sql.json")
    if not os.path.exists(path):
        print("\n(golden_sql.json 없음 — text-to-SQL 트랙 스킵)\n")
        return
    with open(path) as f:
        cases = json.load(f)
    print(f"\n=== Text-to-SQL ({len(cases)} cases) ===\n")
    print(f"{'id':22} {'hit':>4}   expect | generated SQL")
    print("-" * 78)
    hits = 0
    for c in cases:
        try:
            sql_load(c["table"], c["path"], c.get("headerRow", 0))
            resp = sql_ask(c["table"], c["question"])
            blob = json.dumps(resp.get("rows", []), default=str).lower()
            ok = str(c["expect"]).lower() in blob
            sql = (resp.get("sql", "") or "").replace("\n", " ")
        except Exception as e:
            ok, sql = False, f"ERR {e}"
        hits += 1 if ok else 0
        print(f"{c['id']:22} {('Y' if ok else 'N'):>4}   {c['expect']} | {sql[:48]}")
    n = len(cases)
    print(f"\nSQL recall: {hits}/{n} ({hits/n:.0%})\n")


def main():
    if "--sql" in sys.argv:               # 정형 표 트랙 (text-to-SQL)
        sql_run()
        return
    with open(os.path.join(HERE, "golden.json")) as f:   # 비정형 RAG 트랙
        cases = json.load(f)
    recall_sweep(cases)
    if "--judge" in sys.argv:
        judge_run(cases)


if __name__ == "__main__":
    main()
